package io.github.pkyit.notifyx.core.pipeline.filter;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;

/**
 * 一键全局静默（漏斗第 1 环，order=100）。运维止血按钮，必须绝对有效。
 *
 * <p>@implNote 线程安全；静默开关为原子布尔，可由 Admin 运行时切换。</p>
 *
 * @since 1.0
 */
public final class GlobalMuteFilter implements EventFilter {

    /** 框架保留序号。 */
    public static final int ORDER = 100;

    private final AtomicBoolean muted;
    private final boolean muteCritical;

    /**
     * 构造过滤器。
     *
     * @param muted 静默开关
     * @param muteCritical 是否同时静默 CRITICAL（默认 true，一键止血）
     */
    public GlobalMuteFilter(AtomicBoolean muted, boolean muteCritical) {
        this.muted = muted;
        this.muteCritical = muteCritical;
    }

    @Override
    public String name() {
        return "global-mute";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public FilterVerdict doFilter(FilterContext ctx) {
        if (!muted.get()) {
            return FilterVerdict.pass();
        }
        if (!muteCritical && ctx.critical()) {
            return FilterVerdict.pass();
        }
        return FilterVerdict.reject("global-mute");
    }
}