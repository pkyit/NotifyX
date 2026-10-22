package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;

import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * 限流降级包装：Redis 异常时退回内存滑动窗（设计书 §9.4）。
 *
 * <p>降级期间每个实例各自限流，阈值实际被放大 N 倍；这是可用性优先的取舍。</p>
 *
 * <p>@implNote 线程安全；熔断状态由 {@link RedisDegradation} 维护。</p>
 *
 * @since 1.0
 */
public final class FallbackRateLimiter implements RateLimiter {

    private final RateLimiter primary;
    private final RateLimiter fallback;
    private final RedisDegradation degradation;

    /**
     * 构造降级包装。
     *
     * @param primary Redis 实现
     * @param fallback 内存实现
     * @param failureThreshold 连续失败阈值
     * @param openDuration 熔断时长
     */
    public FallbackRateLimiter(RateLimiter primary, RateLimiter fallback, int failureThreshold,
                               Duration openDuration) {
        this.primary = java.util.Objects.requireNonNull(primary, "primary");
        this.fallback = java.util.Objects.requireNonNull(fallback, "fallback");
        this.degradation = new RedisDegradation("rate-limiter", failureThreshold, openDuration);
    }

    @Override
    public boolean tryAcquire(String key, int permits, Duration window) {
        if (permits <= 0) {
            return false;
        }
        if (degradation.isOpen()) {
            return fallback.tryAcquire(key, permits, window);
        }
        try {
            boolean acquired = primary.tryAcquire(key, permits, window);
            degradation.recordSuccess();
            return acquired;
        } catch (RuntimeException error) {
            degradation.recordFailure(error);
            return fallback.tryAcquire(key, permits, window);
        }
    }

    @Override
    public long currentCount(String key, Duration window) {
        if (degradation.isOpen()) {
            return fallback.currentCount(key, window);
        }
        try {
            return primary.currentCount(key, window);
        } catch (RuntimeException error) {
            return fallback.currentCount(key, window);
        }
    }

    /**
     * 是否处于 Redis 降级期（诊断/健康检查用）。
     *
     * @return 是否降级
     */
    public boolean isDegraded() {
        return degradation.isOpen();
    }
}
