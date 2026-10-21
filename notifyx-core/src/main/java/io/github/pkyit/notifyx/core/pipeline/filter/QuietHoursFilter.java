package io.github.pkyit.notifyx.core.pipeline.filter;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;

/**
 * 免打扰时段（漏斗第 3 环，order=300）。CRITICAL 直通，半夜的系统崩溃必须叫醒人。
 *
 * <p>@implNote 不可变配置，线程安全。</p>
 *
 * @since 1.0
 */
public final class QuietHoursFilter implements EventFilter {

    /** 框架保留序号。 */
    public static final int ORDER = 300;

    private final LocalTime from;
    private final LocalTime to;
    private final ZoneId zone;
    private final boolean defer;
    private final boolean criticalBypass;

    /**
     * 构造过滤器。
     *
     * @param from 开始时间
     * @param to 结束时间
     * @param zone 时区
     * @param defer true=延后到结束后，false=直接丢弃
     * @param criticalBypass CRITICAL 是否直通
     */
    public QuietHoursFilter(LocalTime from, LocalTime to, ZoneId zone, boolean defer, boolean criticalBypass) {
        this.from = from;
        this.to = to;
        this.zone = zone;
        this.defer = defer;
        this.criticalBypass = criticalBypass;
    }

    @Override
    public String name() {
        return "quiet-hours";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public FilterVerdict doFilter(FilterContext ctx) {
        if (!inQuietHours(ctx.now())) {
            return FilterVerdict.pass();
        }
        if (criticalBypass && ctx.critical()) {
            return FilterVerdict.pass();
        }
        if (!defer) {
            return FilterVerdict.reject("quiet-hours-drop");
        }
        return FilterVerdict.deferUntil(nextEnd(ctx.now()), "quiet-hours-defer");
    }

    private boolean inQuietHours(Instant instant) {
        LocalTime now = ZonedDateTime.ofInstant(instant, zone).toLocalTime();
        if (from.equals(to)) {
            return false;
        }
        if (from.isBefore(to)) {
            return !now.isBefore(from) && now.isBefore(to);
        }
        return !now.isBefore(from) || now.isBefore(to);
    }

    private Instant nextEnd(Instant instant) {
        ZonedDateTime zoned = ZonedDateTime.ofInstant(instant, zone);
        ZonedDateTime end = zoned.toLocalDate().atTime(to).atZone(zone);
        if (!end.isAfter(zoned)) {
            end = end.plusDays(1);
        }
        return end.toInstant();
    }
}