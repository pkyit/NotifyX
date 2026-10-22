package io.github.pkyit.notifyx.store.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * Redis 降级包装测试（设计书 §9.4）：异常回退内存、熔断后不再打 Redis。
 *
 * @since 1.0
 */
class FallbackStoreTest {

    @Test
    @DisplayName("去重：Redis 连续失败达到阈值后熔断，期间不再调用 Redis")
    void dedupeShouldDegradeAfterFailures() {
        failingDedupe primary = new failingDedupe();
        CountingDedupe fallback = new CountingDedupe();
        FallbackDedupeStore store = new FallbackDedupeStore(primary, fallback, 2, Duration.ofSeconds(30));

        assertThat(store.tryAcquire("k", Duration.ofMinutes(1))).isTrue();
        assertThat(store.tryAcquire("k", Duration.ofMinutes(1))).isFalse();
        assertThat(store.isDegraded()).isTrue();

        assertThat(store.tryAcquire("k", Duration.ofMinutes(1))).isFalse();
        assertThat(primary.calls.get()).isEqualTo(2);
        assertThat(fallback.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("去重：Redis 成功时不降级")
    void dedupeShouldStayOnRedis() {
        CountingDedupe primary = new CountingDedupe();
        CountingDedupe fallback = new CountingDedupe();
        FallbackDedupeStore store = new FallbackDedupeStore(primary, fallback, 2, Duration.ofSeconds(30));

        assertThat(store.tryAcquire("k", Duration.ofMinutes(1))).isTrue();
        assertThat(store.isDegraded()).isFalse();
        assertThat(primary.calls.get()).isEqualTo(1);
        assertThat(fallback.calls.get()).isZero();
    }

    @Test
    @DisplayName("限流：Redis 失败回退内存；permits<=0 直接拒绝")
    void rateLimiterShouldDegrade() {
        failingRateLimiter primary = new failingRateLimiter();
        CountingRateLimiter fallback = new CountingRateLimiter();
        FallbackRateLimiter limiter = new FallbackRateLimiter(primary, fallback, 2, Duration.ofSeconds(30));

        assertThat(limiter.tryAcquire("scene:A", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("scene:A", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("scene:A", 2, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.isDegraded()).isTrue();
        assertThat(limiter.tryAcquire("scene:A", 0, Duration.ofMinutes(1))).isFalse();
        assertThat(primary.calls.get()).isEqualTo(2);
        assertThat(fallback.calls.get()).isEqualTo(3);
    }

    private static final class failingDedupe implements DedupeStore {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean tryAcquire(String dedupeKey, Duration window) {
            calls.incrementAndGet();
            throw new IllegalStateException("redis down");
        }

        @Override
        public void release(String dedupeKey) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public long size() {
            throw new IllegalStateException("redis down");
        }
    }

    private static final class CountingDedupe implements DedupeStore {

        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryAcquire(String dedupeKey, Duration window) {
            calls.incrementAndGet();
            return seen.add(dedupeKey);
        }

        @Override
        public void release(String dedupeKey) {
            seen.remove(dedupeKey);
        }

        @Override
        public long size() {
            return seen.size();
        }
    }

    private static final class failingRateLimiter implements RateLimiter {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean tryAcquire(String key, int permits, Duration window) {
            calls.incrementAndGet();
            throw new IllegalStateException("redis down");
        }

        @Override
        public long currentCount(String key, Duration window) {
            throw new IllegalStateException("redis down");
        }
    }

    private static final class CountingRateLimiter implements RateLimiter {

        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public boolean tryAcquire(String key, int permits, Duration window) {
            calls.incrementAndGet();
            int current = counts.getOrDefault(key, 0);
            if (current >= permits) {
                return false;
            }
            counts.put(key, current + 1);
            return true;
        }

        @Override
        public long currentCount(String key, Duration window) {
            return counts.getOrDefault(key, 0);
        }
    }
}
