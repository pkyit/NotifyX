package io.github.pkyit.notifyx.core.pipeline.filter;

import java.time.Duration;

import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;

/**
 * 去重（漏斗第 4 环，order=400）。
 *
 * <p>CRITICAL 不旁路去重，但窗口减半且不超过 60 秒：既保证持续故障每分钟有一个心跳信号，
 * 又不至于刷屏（设计书 9.2）。</p>
 *
 * <p>@implNote 依赖 {@link DedupeStore} 的原子性，本类自身无状态。</p>
 *
 * @since 1.0
 */
public final class DedupeFilter implements EventFilter {

    /** 框架保留序号。 */
    public static final int ORDER = 400;

    /** CRITICAL 的静默期上限。 */
    private static final Duration CRITICAL_MAX_WINDOW = Duration.ofSeconds(60);

    private final DedupeStore store;

    /**
     * 构造过滤器。
     *
     * @param store 去重存储
     */
    public DedupeFilter(DedupeStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "dedupe";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public FilterVerdict doFilter(FilterContext ctx) {
        Duration window = effectiveWindow(ctx.silentWindow(), ctx.critical());
        if (window.isZero() || window.isNegative()) {
            return FilterVerdict.pass();
        }
        if (!store.tryAcquire(ctx.dedupeKey(), window)) {
            return FilterVerdict.reject("duplicate");
        }
        return FilterVerdict.pass();
    }

    private static Duration effectiveWindow(Duration configured, boolean critical) {
        if (!critical) {
            return configured;
        }
        Duration halved = configured.dividedBy(2);
        return halved.compareTo(CRITICAL_MAX_WINDOW) > 0 ? CRITICAL_MAX_WINDOW : halved;
    }
}