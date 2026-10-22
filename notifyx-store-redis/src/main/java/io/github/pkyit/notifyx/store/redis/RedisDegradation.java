package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 调用降级守卫：连续失败达到阈值后，在 {@code openDuration} 内直接走内存实现，
 * 避免每个通知都等一次连接超时（设计书 §9.4）。
 *
 * <p>@implNote 线程安全；失败计数在成功时清零。</p>
 *
 * @since 1.0
 */
final class RedisDegradation {

    private static final Logger log = LoggerFactory.getLogger(RedisDegradation.class);

    private final String target;
    private final int failureThreshold;
    private final long openMillis;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long openUntil;

    RedisDegradation(String target, int failureThreshold, Duration openDuration) {
        this.target = target;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1000L, openDuration == null ? 30_000L : openDuration.toMillis());
    }

    boolean isOpen() {
        return System.currentTimeMillis() < openUntil;
    }

    void recordSuccess() {
        failures.set(0);
    }

    void recordFailure(RuntimeException error) {
        if (failures.incrementAndGet() >= failureThreshold) {
            openUntil = System.currentTimeMillis() + openMillis;
            failures.set(0);
            log.warn("notifyx redis {} unavailable, degrade to memory for {}s: {}",
                    target, openMillis / 1000, error.toString());
        }
    }
}
