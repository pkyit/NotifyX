package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内存滑动窗口限流器测试（设计书 9.3 / 9.4 的 T0 兜底实现）。
 *
 * @since 1.0
 */
class MemoryRateLimiterTest {

    private final MemoryRateLimiter limiter = new MemoryRateLimiter();

    @Test
    @DisplayName("窗口内超过阈值后拒绝")
    void shouldRejectAfterPermits() {
        Duration window = Duration.ofMinutes(1);

        assertThat(limiter.tryAcquire("scene:A", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("scene:A", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("scene:A", 2, window)).isFalse();
        assertThat(limiter.currentCount("scene:A", window)).isEqualTo(2);
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void shouldIsolateKeys() {
        Duration window = Duration.ofMinutes(1);
        assertThat(limiter.tryAcquire("scene:A", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("scene:B", 1, window)).isTrue();
        assertThat(limiter.currentCount("scene:A", window)).isEqualTo(1);
        assertThat(limiter.currentCount("scene:B", window)).isEqualTo(1);
        assertThat(limiter.currentCount("scene:C", window)).isZero();
    }

    @Test
    @DisplayName("窗口滑过后重新放行")
    void shouldWindowSlide() throws Exception {
        Duration window = Duration.ofMillis(30);
        assertThat(limiter.tryAcquire("k", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("k", 1, window)).isFalse();

        Thread.sleep(50);
        assertThat(limiter.tryAcquire("k", 1, window)).isTrue();
    }

    @Test
    @DisplayName("permits <= 0 直接拒绝")
    void shouldRejectNonPositivePermits() {
        assertThat(limiter.tryAcquire("k", 0, Duration.ofSeconds(1))).isFalse();
        assertThat(limiter.tryAcquire("k", -1, Duration.ofSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("currentCount 按窗口清理过期计数，不返回陈旧值")
    void currentCountShouldPruneExpired() throws Exception {
        Duration window = Duration.ofMillis(30);
        assertThat(limiter.tryAcquire("k", 1, window)).isTrue();
        assertThat(limiter.currentCount("k", window)).isEqualTo(1);

        Thread.sleep(50);

        assertThat(limiter.currentCount("k", window)).isZero();
    }

    @Test
    @DisplayName("evictExpired 清理过期 key，防止 map 无限增长")
    void shouldEvictExpiredKeys() throws Exception {
        limiter.tryAcquire("old", 1, Duration.ofMillis(1));
        Thread.sleep(20);
        limiter.tryAcquire("new", 1, Duration.ofMinutes(1));

        int evicted = limiter.evictExpired(Duration.ofMillis(10));

        assertThat(evicted).isEqualTo(1);
        assertThat(limiter.currentCount("new", Duration.ofMinutes(1))).isEqualTo(1);
    }
}
