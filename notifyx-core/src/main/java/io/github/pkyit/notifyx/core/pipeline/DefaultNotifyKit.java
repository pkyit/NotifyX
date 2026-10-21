package io.github.pkyit.notifyx.core.pipeline;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.spi.TraceContext;
import io.github.pkyit.notifyx.core.support.MdcTraceContext;

/**
 * {@link NotifyKit} 默认实现：组装事件后提交给管线。
 *
 * <p>异步模式下调用线程只做事件组装与入队，耗时可控在毫秒级；同步模式仅用于
 * {@code event.async(false)} 的极少数「必须确认送达」场景。</p>
 *
 * <p>traceId/spanId 在<b>调用线程</b>抓取并写进事件标签：异步投递后 MDC 已不可见，
 * 这一步是「告警里能带上链路 ID」的关键（设计书 15.3）。</p>
 *
 * <p>@implNote 线程安全；{@code executor} 为 null 时退化为同步执行。</p>
 *
 * @since 1.0
 */
public final class DefaultNotifyKit implements NotifyKit {

    private static final Logger log = LoggerFactory.getLogger(DefaultNotifyKit.class);

    /** 默认异常告警场景。 */
    public static final String UNCAUGHT_EXCEPTION_SCENE = "UNCAUGHT_EXCEPTION";

    /** 堆栈最大保留行数。 */
    private static final int MAX_STACK_LINES = 30;

    private final NotifyPipeline pipeline;
    private final NotifyTaskExecutor executor;
    private final TraceContext traceContext;

    /**
     * 构造门面，默认从 MDC 读取 trace。
     *
     * @param pipeline 管线
     * @param executor 异步执行器；null 表示同步
     */
    public DefaultNotifyKit(NotifyPipeline pipeline, NotifyTaskExecutor executor) {
        this(pipeline, executor, new MdcTraceContext());
    }

    /**
     * 构造门面。
     *
     * @param pipeline 管线
     * @param executor 异步执行器；null 表示同步
     * @param traceContext trace 读取口，null 表示不采集
     */
    public DefaultNotifyKit(NotifyPipeline pipeline, NotifyTaskExecutor executor, TraceContext traceContext) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.executor = executor;
        this.traceContext = traceContext == null ? TraceContext.none() : traceContext;
    }

    @Override
    public void send(NotifyEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.async() && executor != null) {
            try {
                executor.execute(event.level(), event.eventId(), () -> pipeline.handle(event));
            } catch (RejectedExecutionException ex) {
                log.warn("notifyx queue rejected event scene={} eventId={}", event.scene(), event.eventId());
            }
        } else {
            pipeline.handle(event);
        }
    }

    /**
     * 同步投递并返回结果（失败补偿等需要明确成败的场景使用）。
     *
     * @param event 事件
     * @return 是否成功投递
     */
    public boolean deliverSync(NotifyEvent event) {
        Objects.requireNonNull(event, "event");
        return pipeline.handle(event);
    }

    @Override
    public void sendAsync(NotifyEvent event) {
        Objects.requireNonNull(event, "event");
        if (executor == null) {
            pipeline.handle(event);
            return;
        }
        try {
            executor.execute(event.level(), event.eventId(), () -> pipeline.handle(event));
        } catch (RejectedExecutionException ex) {
            log.warn("notifyx queue rejected event scene={} eventId={}", event.scene(), event.eventId());
        }
    }

    @Override
    public void send(String scene, Map<String, ?> params) {
        send(traced(scene).params(params).build());
    }

    @Override
    public void send(String scene, String receiver, Map<String, ?> params) {
        send(traced(scene).receiver(receiver).params(params).build());
    }

    @Override
    public void alarm(Throwable throwable) {
        alarm(UNCAUGHT_EXCEPTION_SCENE, throwable);
    }

    @Override
    public void alarm(String scene, Throwable throwable, Object... context) {
        Objects.requireNonNull(scene, "scene");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("exception", throwable == null ? "" : throwable.getClass().getSimpleName());
        params.put("exceptionMessage", throwable == null ? "" : String.valueOf(throwable.getMessage()));
        params.put("stack", stackOf(throwable));
        if (context != null) {
            flatten(context, params);
        }
        send(traced(scene)
                .level(Level.ERROR)
                .params(params)
                .build());
    }

    /**
     * 事件构建器，带上调用线程的 trace 标签。
     *
     * @param scene 场景码
     * @return 构建器
     */
    private NotifyEvent.Builder traced(String scene) {
        NotifyEvent.Builder builder = NotifyEvent.builder(scene);
        String traceId = traceContext.traceId();
        if (traceId != null) {
            builder.tag("traceId", traceId);
        }
        String spanId = traceContext.spanId();
        if (spanId != null) {
            builder.tag("spanId", spanId);
        }
        return builder;
    }

    private static void flatten(Object[] context, Map<String, Object> params) {
        int index = 0;
        for (Object item : context) {
            if (item == null) {
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                map.forEach((key, value) -> params.put(String.valueOf(key), value));
            } else if (item instanceof Throwable throwable) {
                params.put("exception", throwable.getClass().getSimpleName());
                params.put("exceptionMessage", String.valueOf(throwable.getMessage()));
                params.put("stack", stackOf(throwable));
            } else if (item instanceof String text && text.contains("=")) {
                int split = text.indexOf('=');
                params.put(text.substring(0, split), text.substring(split + 1));
            } else if (item instanceof String text) {
                params.put("arg" + index, text);
            } else {
                params.put("ctx" + index, String.valueOf(item));
            }
            index++;
        }
    }

    private static String stackOf(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String[] lines = writer.toString().split("\\R");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, MAX_STACK_LINES); i++) {
            builder.append(lines[i]).append('\n');
        }
        return builder.toString().trim();
    }
}
