package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 熔断器状态机测试（设计书 9.7）：打开、拒绝、半开试探、成功关闭、失败退避翻倍。
 *
 * @since 1.0
 */
class CircuitBreakerTest {

    @Test
    @DisplayName("连续失败达到阈值才打开，打开期间拒绝请求")
    void shouldOpenAfterThreshold() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(50), Duration.ofMillis(200));

        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();

        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.remainingOpenMillis()).isPositive();
    }

    @Test
    @DisplayName("打开到期后进入半开，只放一条试探")
    void shouldProbeOnlyOnceWhenHalfOpen() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofMillis(30), Duration.ofMillis(200));
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(60);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.allowRequest()).as("probe in flight").isFalse();
    }

    @Test
    @DisplayName("半开时成功 → 关闭；失败 → 重新打开且打开时长翻倍")
    void shouldCloseOnSuccessAndBackOffOnFailure() throws Exception {
        CircuitBreaker success = new CircuitBreaker(1, Duration.ofMillis(30), Duration.ofMillis(200));
        success.recordFailure();
        Thread.sleep(60);
        assertThat(success.allowRequest()).isTrue();
        success.recordSuccess();
        assertThat(success.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(success.remainingOpenMillis()).isZero();

        CircuitBreaker failure = new CircuitBreaker(1, Duration.ofMillis(30), Duration.ofMillis(200));
        failure.recordFailure();
        Thread.sleep(60);
        assertThat(failure.allowRequest()).isTrue();
        failure.recordFailure();
        assertThat(failure.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(failure.remainingOpenMillis()).isBetween(1L, 100L);
    }

    @Test
    @DisplayName("打开时长不会超过上限")
    void shouldCapOpenDuration() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofMillis(100), Duration.ofMillis(100));
        breaker.recordFailure();
        assertThat(breaker.remainingOpenMillis()).isBetween(1L, 150L);
    }
}
