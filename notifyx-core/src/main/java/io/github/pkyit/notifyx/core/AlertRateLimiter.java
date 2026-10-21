package io.github.pkyit.notifyx.core;

/**
 * 告警限流扩展点。
 *
 * <p>调度器在真正投递前调用本接口，返回 {@code false} 表示本次事件被抑制（不投递）。
 * 默认实现见 {@link SlidingWindowRateLimiter}；业务方也可以替换为基于 Redis 的分布式实现。</p>
 */
@FunctionalInterface
public interface AlertRateLimiter {

    /**
     * 尝试获取一次投递许可。
     *
     * @param key 限流键，通常为 {@link AlertEvent#fingerprint()}
     * @return {@code true} 允许投递；{@code false} 本次抑制
     */
    boolean tryAcquire(String key);

    /**
     * 永远放行的限流器，作为默认实现。
     */
    static AlertRateLimiter unlimited() {
        return key -> true;
    }
}
