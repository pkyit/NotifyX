package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 跨实例聚合配置，复用 {@code notifyx.aggregate.*}（与 T0 同一个开关）。
 *
 * <p>Redis 存在且 {@code enabled=true} 时由 {@link RedisStoresAutoConfiguration} 装配
 * {@link RedisAggregator}，并让 T0 聚合器让位。</p>
 *
 * <p>@implNote 可变 POJO，仅在启动期绑定。</p>
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.aggregate")
public class RedisAggregationProperties {

    /** 是否启用聚合（T0/T1 共用开关）。 */
    private boolean enabled;

    /** 聚合窗口。 */
    private Duration window = Duration.ofSeconds(10);

    /** 本地待调度键上限，防止异常流量撑大内存。 */
    private int maxEntries = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }
}
