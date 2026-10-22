package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 存储配置（{@code notifyx.store.redis.*}）。
 *
 * <p>@implNote 可变 POJO，仅在启动期绑定。</p>
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.store.redis")
public class RedisStoreProperties {

    /** 是否启用 Redis 存储；Redis 不可用时自动降级为内存。 */
    private boolean enabled = true;

    /** 键前缀，便于多环境共用实例。 */
    private String keyPrefix = "nx:";

    /** 连续失败多少次后熔断 Redis（避免每个事件都等超时）。 */
    private int failureThreshold = 3;

    /** 熔断时长；期间直接走内存降级。 */
    private Duration openDuration = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = openDuration;
    }
}
