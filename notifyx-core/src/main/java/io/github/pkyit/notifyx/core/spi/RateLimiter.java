package io.github.pkyit.notifyx.core.spi;

import java.time.Duration;

/**
 * 限流存储 SPI：滑动窗口计数。
 *
 * <p>{@code permits} 表示「窗口内允许的最大次数」（即阈值），每次调用消耗 1 个许可。
 * 之所以把阈值放在调用参数而不是存储构造参数上，是为了让同一份存储服务多个不同规则。</p>
 *
 * <p>@implNote 实现类必须线程安全；{@link #tryAcquire} 必须是原子操作。</p>
 *
 * @since 1.0
 */
public interface RateLimiter {

    /**
     * 尝试获取一个许可。
     *
     * @param key 限流键，通常为 {@code scene:CODE} 或 {@code global}
     * @param permits 窗口内允许的最大次数，小于等于 0 时视为不放行
     * @param window 窗口
     * @return true=获取成功
     */
    boolean tryAcquire(String key, int permits, Duration window);

    /**
     * 当前窗口已用量，诊断用（允许有轻微误差）。
     *
     * @param key 限流键
     * @param window 窗口
     * @return 已用量
     */
    long currentCount(String key, Duration window);
}