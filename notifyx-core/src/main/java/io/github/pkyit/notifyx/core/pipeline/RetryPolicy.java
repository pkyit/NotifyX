package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.util.SplittableRandom;

import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * 重试策略：指数退避 + 随机抖动（设计书 9.8）。
 *
 * <pre>
 * delay(attempt) = min(base * 2^(attempt-1), maxDelay) * (1 - jitter + jitter * random)
 * </pre>
 *
 * <p>@implNote 无状态（除随机数发生器），线程安全。</p>
 *
 * @since 1.0
 */
public final class RetryPolicy {

    private static final SplittableRandom RANDOM = new SplittableRandom();

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitter;

    /**
     * 构造策略。
     *
     * @param maxAttempts 最大尝试次数，至少 1
     * @param baseDelay 退避基数
     * @param maxDelay 退避上限
     * @param jitter 抖动系数，取值 [0,1)
     */
    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, double jitter) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelay = baseDelay == null ? Duration.ofSeconds(2) : baseDelay;
        this.maxDelay = maxDelay == null ? Duration.ofSeconds(60) : maxDelay;
        this.jitter = Math.min(0.95, Math.max(0.0, jitter));
    }

    /**
     * 默认策略：3 次尝试、2s 基数、60s 上限、0.5 抖动。
     *
     * @return 默认策略
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofSeconds(60), 0.5);
    }

    /**
     * 计算某渠道的最大尝试次数。
     *
     * @param channel 渠道，可为 null
     * @return 尝试次数
     */
    public int attemptsFor(NotifyChannel channel) {
        if (channel == null) {
            return maxAttempts;
        }
        int channelAttempts = channel.maxRetries();
        return channelAttempts <= 0 ? maxAttempts : channelAttempts;
    }

    /**
     * 计算第 attempt 次失败后的退避时长。
     *
     * @param attempt 已失败次数，从 1 开始
     * @param channel 渠道，可为 null
     * @return 退避时长
     */
    public Duration delayAfter(int attempt, NotifyChannel channel) {
        Duration base = channel == null ? baseDelay : channel.retryBackoff();
        long exponential = base.toMillis() * (1L << Math.min(20, Math.max(0, attempt - 1)));
        long capped = Math.min(exponential, maxDelay.toMillis());
        double factor = 1.0 - jitter + jitter * RANDOM.nextDouble();
        return Duration.ofMillis(Math.max(1L, (long) (capped * factor)));
    }
}
