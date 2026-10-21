package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * 重试策略测试（设计书 9.8）：指数退避、上限、抖动区间、渠道覆盖。
 *
 * @since 1.0
 */
class RetryPolicyTest {

    @Test
    @DisplayName("无抖动时严格指数退避并受 maxDelay 限制")
    void shouldBackOffExponentially() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), Duration.ofMillis(500), 0.0);

        assertThat(policy.delayAfter(1, null)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayAfter(2, null)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delayAfter(3, null)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.delayAfter(4, null)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayAfter(20, null)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayAfter(0, null)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("抖动落在 [base*(1-jitter), base] 区间内")
    void shouldApplyJitterWithinBounds() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1000), Duration.ofSeconds(60), 0.5);

        for (int i = 0; i < 50; i++) {
            Duration delay = policy.delayAfter(1, null);
            assertThat(delay.toMillis()).isBetween(500L, 1000L);
        }
    }

    @Test
    @DisplayName("渠道可通过 maxRetries / retryBackoff 覆盖默认值")
    void shouldRespectChannelOverrides() {
        RetryPolicy policy = RetryPolicy.defaults();
        NotifyChannel channel = new StubChannel(1, Duration.ofMillis(10));

        assertThat(policy.attemptsFor(channel)).isEqualTo(1);
        assertThat(policy.attemptsFor(null)).isEqualTo(3);
        assertThat(policy.attemptsFor(new StubChannel(0, Duration.ofMillis(10)))).isEqualTo(3);
        assertThat(policy.delayAfter(1, channel).toMillis()).isBetween(5L, 10L);
    }

    @Test
    @DisplayName("默认策略参数：3 次 / 2s 基数 / 60s 上限")
    void shouldProvideDefaults() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.attemptsFor(null)).isEqualTo(3);
        assertThat(policy.delayAfter(1, null).toMillis()).isBetween(1000L, 2000L);
    }

    private static final class StubChannel implements NotifyChannel {

        private final int maxRetries;
        private final Duration backoff;

        private StubChannel(int maxRetries, Duration backoff) {
            this.maxRetries = maxRetries;
            this.backoff = backoff;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public int maxRetries() {
            return maxRetries;
        }

        @Override
        public Duration retryBackoff() {
            return backoff;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            return SendResult.success("id", null, Duration.ZERO);
        }
    }
}
