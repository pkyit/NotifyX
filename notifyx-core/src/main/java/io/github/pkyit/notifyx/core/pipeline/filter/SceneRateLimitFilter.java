package io.github.pkyit.notifyx.core.pipeline.filter;

import java.time.Duration;
import java.util.Map;

import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * 场景级限流（漏斗第 5 环，order=500）。CRITICAL 直通——限流拦掉 CRITICAL 等于自欺欺人。
 *
 * <p>@implNote 无可变状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class SceneRateLimitFilter implements EventFilter {

    /** 框架保留序号。 */
    public static final int ORDER = 500;

    private final RateLimiter rateLimiter;
    private final Map<String, Rule> rules;

    /**
     * 构造过滤器。
     *
     * @param rateLimiter 限流存储
     * @param rules 场景规则，key 为场景码
     */
    public SceneRateLimitFilter(RateLimiter rateLimiter, Map<String, Rule> rules) {
        this.rateLimiter = rateLimiter;
        this.rules = Map.copyOf(rules);
    }

    @Override
    public String name() {
        return "scene-rate-limit";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public FilterVerdict doFilter(FilterContext ctx) {
        if (ctx.critical()) {
            return FilterVerdict.pass();
        }
        Rule rule = rules.get(ctx.event().scene());
        if (rule == null || rule.limit() <= 0) {
            return FilterVerdict.pass();
        }
        boolean acquired = rateLimiter.tryAcquire("scene:" + ctx.event().scene(), rule.limit(), rule.window());
        return acquired ? FilterVerdict.pass() : FilterVerdict.reject("rate-limited-scene");
    }

    /**
     * 场景限流规则。
     *
     * @param limit 窗口内允许的条数
     * @param window 窗口
     * @since 1.0
     */
    public record Rule(int limit, Duration window) {
    }
}
