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

/**
 * {@link NotifyKit} 默认实现：组装事件后提交给管线。
 *
 * <p>异步模式下调用线程只做事件组装与入队，耗时可控在毫秒级；同步模式仅用于
 * {@code event.async(false)} 的极少数「必须确认送达」场景。</p>
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

    /**
     * 构造门面。
     *
     * @param pipeline 管线
     * @param executor 异步执行器；null 表示同步
     */
    public DefaultNotifyKit(NotifyPipeline pipeline, NotifyTaskExecutor executor) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.executor = executor;
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
        send(NotifyEvent.builder(scene).params(params).build());
    }

    @Override
    public void send(String scene, String receiver, Map<String, ?> params) {
        send(NotifyEvent.builder(scene).receiver(receiver).params(params).build());
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
        send(NotifyEvent.builder(scene)
                .level(Level.ERROR)
                .params(params)
                .build());
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
