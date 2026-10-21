package io.github.pkyit.notifyx.core.pipeline.filter;

import java.time.Duration;

import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * 全局限流（漏斗第 6 环，order=600）：防通知风暴的总闸。CRITICAL 直通。
 *
 * <p>@implNote 无可变状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class GlobalRateLimitFilter implements EventFilter {

    /** 框架保留序号。 */
    public static final int ORDER = 600;

    private final RateLimiter rateLimiter;
    private final int limit;
    private final Duration window;

    /**
     * 构造过滤器。
     *
     * @param rateLimiter 限流存储
     * @param limit 窗口内允许的条数
     * @param window 窗口
     */
    public GlobalRateLimitFilter(RateLimiter rateLimiter, int limit, Duration window) {
        this.rateLimiter = rateLimiter;
        this.limit = limit;
        this.window = window;
    }

    @Override
    public String name() {
        return "global-rate-limit";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public FilterVerdict doFilter(FilterContext ctx) {
        if (ctx.critical() || limit <= 0) {
            return FilterVerdict.pass();
        }
        boolean acquired = rateLimiter.tryAcquire("global", limit, window);
        return acquired ? FilterVerdict.pass() : FilterVerdict.reject("rate-limited-global");
    }
}
