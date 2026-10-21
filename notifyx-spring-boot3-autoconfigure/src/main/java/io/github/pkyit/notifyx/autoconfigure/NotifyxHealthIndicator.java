package io.github.pkyit.notifyx.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import io.github.pkyit.notifyx.core.model.HealthStatus;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * Actuator 健康指示器（设计书 §15.2）。
 *
 * <p>默认只报「装配状态」，<b>不做真实探活</b>：健康检查会被 K8s 每 10 秒调用一次，
 * 每次都真发消息既制造垃圾又产生真实费用。只有
 * {@code notifyx.actuator.health.probe-enabled=true} 时才调用 {@link NotifyChannel#health()}。</p>
 *
 * <p>状态映射：UP 正常；DEGRADED 去重/限流降级为内存或部分渠道不可用（K8s readiness 仍视为 UP）；
 * DOWN 全部渠道不可用或全局静默；OUT_OF_SERVICE 整体关闭。</p>
 *
 * <p>@implNote 无状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class NotifyxHealthIndicator implements HealthIndicator {

    private final NotifyxProperties properties;
    private final List<NotifyChannel> channels;
    private final DedupeStore dedupeStore;
    private final RateLimiter rateLimiter;
    private final FailureStore failureStore;
    private final boolean probeEnabled;

    /**
     * 构造健康指示器。
     *
     * @param properties 配置
     * @param channels 渠道
     * @param dedupeStore 去重存储
     * @param rateLimiter 限流器
     * @param failureStore 失败存储
     */
    public NotifyxHealthIndicator(NotifyxProperties properties, List<NotifyChannel> channels,
                                  DedupeStore dedupeStore, RateLimiter rateLimiter, FailureStore failureStore) {
        this.properties = properties;
        this.channels = List.copyOf(channels);
        this.dedupeStore = dedupeStore;
        this.rateLimiter = rateLimiter;
        this.failureStore = failureStore;
        this.probeEnabled = properties.getActuator().getHealth().isProbeEnabled();
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.outOfService().withDetail("reason", "notifyx.enabled=false").build();
        }
        if (properties.getGlobalMute().isEnabled()) {
            return Health.down().withDetail("reason", "global mute enabled").build();
        }
        if (channels.isEmpty()) {
            return Health.down().withDetail("reason", "no NotifyChannel registered").build();
        }

        List<String> downChannels = new ArrayList<>();
        if (probeEnabled) {
            for (NotifyChannel channel : channels) {
                if (channel.health() == HealthStatus.DOWN) {
                    downChannels.add(channel.name());
                }
            }
            if (downChannels.size() == channels.size()) {
                return down()
                        .withDetail("reason", "all channels are down")
                        .withDetail("channels", channelNames())
                        .build();
            }
        }

        boolean degraded = !downChannels.isEmpty() || isMemory(dedupeStore) || isMemory(rateLimiter);
        Health.Builder builder = degraded ? Health.status("DEGRADED") : Health.up();
        builder.withDetail("mode", properties.getMode())
                .withDetail("channels", channelNames())
                .withDetail("dedupe", simpleName(dedupeStore))
                .withDetail("rateLimiter", simpleName(rateLimiter))
                .withDetail("failureStore", simpleName(failureStore))
                .withDetail("probeEnabled", probeEnabled);
        if (!downChannels.isEmpty()) {
            builder.withDetail("downChannels", downChannels);
        }
        if (isMemory(dedupeStore) || isMemory(rateLimiter)) {
            builder.withDetail("degradedReason", "in-memory dedupe/rate limiter: multi-instance may duplicate");
        }
        return builder.build();
    }

    private Health.Builder down() {
        return Health.down()
                .withDetail("mode", properties.getMode())
                .withDetail("dedupe", simpleName(dedupeStore))
                .withDetail("rateLimiter", simpleName(rateLimiter))
                .withDetail("failureStore", simpleName(failureStore));
    }

    private List<String> channelNames() {
        List<String> names = new ArrayList<>(channels.size());
        for (NotifyChannel channel : channels) {
            names.add(channel.name());
        }
        return names;
    }

    private static boolean isMemory(Object spi) {
        if (spi == null) {
            return false;
        }
        String name = spi.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("memory") || name.contains("inmemory");
    }

    private static String simpleName(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName();
    }
}
