package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;

import io.github.pkyit.notifyx.core.spi.DedupeStore;

/**
 * 去重降级包装：Redis 异常时退回内存实现，绝不让通知发送失败（设计书 §9.4）。
 *
 * <p>降级期间多实例各自去重，可能重复发送——重复发比不发好，该行为在 README 中说明。</p>
 *
 * <p>@implNote 线程安全；熔断状态由 {@link RedisDegradation} 维护。</p>
 *
 * @since 1.0
 */
public final class FallbackDedupeStore implements DedupeStore {

    private final DedupeStore primary;
    private final DedupeStore fallback;
    private final RedisDegradation degradation;

    /**
     * 构造降级包装。
     *
     * @param primary Redis 实现
     * @param fallback 内存实现
     * @param failureThreshold 连续失败阈值
     * @param openDuration 熔断时长
     */
    public FallbackDedupeStore(DedupeStore primary, DedupeStore fallback, int failureThreshold,
                               Duration openDuration) {
        this.primary = java.util.Objects.requireNonNull(primary, "primary");
        this.fallback = java.util.Objects.requireNonNull(fallback, "fallback");
        this.degradation = new RedisDegradation("dedupe", failureThreshold, openDuration);
    }

    @Override
    public boolean tryAcquire(String dedupeKey, Duration window) {
        if (degradation.isOpen()) {
            return fallback.tryAcquire(dedupeKey, window);
        }
        try {
            boolean acquired = primary.tryAcquire(dedupeKey, window);
            degradation.recordSuccess();
            return acquired;
        } catch (RuntimeException error) {
            degradation.recordFailure(error);
            return fallback.tryAcquire(dedupeKey, window);
        }
    }

    @Override
    public void release(String dedupeKey) {
        try {
            primary.release(dedupeKey);
        } catch (RuntimeException ignored) {
            // 释放失败不影响主流程，内存侧仍会清理
        }
        fallback.release(dedupeKey);
    }

    @Override
    public long size() {
        if (degradation.isOpen()) {
            return fallback.size();
        }
        try {
            return primary.size();
        } catch (RuntimeException error) {
            return fallback.size();
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
