package io.github.pkyit.notifyx.autoconfigure;

import java.time.Duration;
import java.util.Objects;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer 指标实现（默认前缀 {@code notifyx.}）。
 *
 * <p>标签只使用低基数维度：channel / scene / status / source / reason / level。</p>
 *
 * <p>@implNote 线程安全（Micrometer 计量器本身线程安全）。</p>
 *
 * @since 1.0
 */
public final class MicrometerNotifyMetrics implements NotifyMetrics {

    private final MeterRegistry registry;
    private final String prefix;

    /**
     * 使用默认前缀构造指标出口。
     *
     * @param registry 计量注册表
     */
    public MicrometerNotifyMetrics(MeterRegistry registry) {
        this(registry, "notifyx");
    }

    /**
     * 构造指标出口。
     *
     * @param registry 计量注册表
     * @param prefix 指标名前缀，空值回退为 {@code notifyx}；末尾点会被去掉
     */
    public MicrometerNotifyMetrics(MeterRegistry registry, String prefix) {
        this.registry = Objects.requireNonNull(registry, "registry");
        String normalized = prefix == null || prefix.isBlank() ? "notifyx" : prefix.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.prefix = normalized.isEmpty() ? "notifyx" : normalized;
    }

    @Override
    public void eventReceived(NotifyEvent event, String source) {
        Counter.builder(metricName("event.received"))
                .tag("scene", event.scene())
                .tag("level", event.level().name())
                .tag("source", source)
                .register(registry)
                .increment();
    }

    @Override
    public void sendResult(String channel, String scene, SendResult.Status status, Duration cost) {
        String statusTag = status == null ? "unknown" : status.name().toLowerCase();
        Counter.builder(metricName("send.total"))
                .tag("channel", channel)
                .tag("scene", scene)
                .tag("status", statusTag)
                .register(registry)
                .increment();
        if (cost != null && !cost.isZero()) {
            Timer.builder(metricName("send.duration"))
                    .tag("channel", channel)
                    .tag("scene", scene)
                    .register(registry)
                    .record(cost);
        }
    }

    @Override
    public void queueDiscarded(String reason, Level level) {
        Counter.builder(metricName("queue.discarded"))
                .tag("reason", reason)
                .tag("level", level == null ? "UNKNOWN" : level.name())
                .register(registry)
                .increment();
    }

    @Override
    public void filterRejected(String filter, String scene) {
        Counter.builder(metricName("filter.rejected"))
                .tag("filter", filter)
                .tag("scene", scene)
                .register(registry)
                .increment();
    }

    private String metricName(String suffix) {
        return prefix + "." + suffix;
    }
}
