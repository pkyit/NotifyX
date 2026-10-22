package io.github.pkyit.notifyx.core.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyBus;
import io.github.pkyit.notifyx.core.spi.RecordStatus;
import io.github.pkyit.notifyx.core.support.Fingerprints;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * MQ 模式门面（设计书 §11.3）：producer-only。
 *
 * <p>只做三件事：序列化为 {@link NotifyEnvelope}、{@link NotifyBus#publish}、
 * 失败写 {@link FailureStore} 兜底。去重/限流/聚合/渲染全部由独立 consumer 负责。</p>
 *
 * <p>MQ 模式下 {@code onSuccess/onError} 无法跨进程，检测到回调时只 warn，不阻断发送。</p>
 *
 * <p>@implNote 线程安全；bus 发布失败不向业务抛异常（通知永远旁路）。</p>
 *
 * @since 1.0
 */
public final class MqNotifyKit implements NotifyKit {

    private static final Logger log = LoggerFactory.getLogger(MqNotifyKit.class);

    private final NotifyBus bus;
    private final FailureStore failureStore;
    private final NotifyMetrics metrics;
    private final String instanceId;

    /**
     * 构造门面。
     *
     * @param bus 总线
     * @param failureStore 失败兜底存储
     * @param metrics 指标
     * @param instanceId 实例 ID
     */
    public MqNotifyKit(NotifyBus bus, FailureStore failureStore, NotifyMetrics metrics, String instanceId) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.failureStore = failureStore;
        this.metrics = metrics == null ? NotifyMetrics.noop() : metrics;
        this.instanceId = instanceId;
    }

    @Override
    public void send(NotifyEvent event) {
        Objects.requireNonNull(event, "event");
        warnCallbacks(event);
        metrics.eventReceived(event, "L4");
        String dedupeKey = event.dedupeKey() == null || event.dedupeKey().isBlank()
                ? Fingerprints.of(event.scene(), event.bizId(), event.params())
                : event.dedupeKey();
        NotifyEnvelope envelope = NotifyEnvelope.of(event, event.eventId(), dedupeKey,
                MDC.get("traceId"), MDC.get("spanId"), instanceId, 0, System.currentTimeMillis());
        try {
            bus.publish(envelope);
        } catch (RuntimeException ex) {
            log.warn("notifyx mq publish failed scene={} eventId={}: {}", event.scene(), event.eventId(),
                    ex.toString());
            persist(event, envelope, ex);
            notifyError(event, ex);
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
        alarm("UNCAUGHT_EXCEPTION", throwable);
    }

    @Override
    public void alarm(String scene, Throwable throwable, Object... context) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("exception", throwable == null ? "unknown" : throwable.getClass().getSimpleName());
        params.put("exceptionMessage", throwable == null ? "" : String.valueOf(throwable.getMessage()));
        if (context != null && context.length > 0) {
            params.put("context", context);
        }
        send(NotifyEvent.builder(scene).level(Level.ERROR).params(params).build());
    }

    @Override
    public void sendAsync(NotifyEvent event) {
        send(event);
    }

    private void persist(NotifyEvent event, NotifyEnvelope envelope, RuntimeException error) {
        if (failureStore == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            failureStore.save(new FailedRecord(event.eventId(), RecordStatus.PENDING, 0,
                    NotifyEnvelopeCodec.toJson(envelope), bus.name(), String.valueOf(error.getMessage()),
                    0, now, now + 60_000L));
        } catch (RuntimeException persistError) {
            log.warn("notifyx mq fallback persist failed eventId={}: {}", event.eventId(),
                    persistError.toString());
        }
    }

    private static void notifyError(NotifyEvent event, RuntimeException error) {
        Consumer<Throwable> callback = event.onError();
        if (callback != null) {
            try {
                callback.accept(error);
            } catch (RuntimeException ignored) {
                // 回调是旁路
            }
        }
    }

    private static void warnCallbacks(NotifyEvent event) {
        if (event.onSuccess() != null || event.onError() != null) {
            log.warn("notifyx mq mode ignores onSuccess/onError callbacks (not serializable), eventId={}",
                    event.eventId());
        }
    }
}
