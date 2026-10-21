package io.github.pkyit.notifyx.core.pipeline.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.pipeline.SceneConfig;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;

/**
 * 漏斗过滤器测试：全局静默（第 1 环）与免打扰（第 3 环）。
 *
 * @since 1.0
 */
class FilterBehaviorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("全局静默：未静默放行；静默后 CRITICAL 是否旁路由 muteCritical 决定")
    void shouldMuteGlobally() {
        AtomicBoolean muted = new AtomicBoolean(false);
        GlobalMuteFilter filter = new GlobalMuteFilter(muted, true);

        assertThat(filter.doFilter(context(Level.ERROR, Instant.now())).isPass()).isTrue();

        muted.set(true);
        assertThat(filter.doFilter(context(Level.ERROR, Instant.now())).decision())
                .isEqualTo(FilterVerdict.Decision.REJECT);
        assertThat(filter.doFilter(context(Level.CRITICAL, Instant.now())).decision())
                .isEqualTo(FilterVerdict.Decision.REJECT);
        assertThat(filter.name()).isEqualTo("global-mute");
        assertThat(filter.order()).isEqualTo(GlobalMuteFilter.ORDER);
    }

    @Test
    @DisplayName("muteCritical=false 时 CRITICAL 穿透全局静默")
    void shouldLetCriticalBypassGlobalMute() {
        GlobalMuteFilter filter = new GlobalMuteFilter(new AtomicBoolean(true), false);
        assertThat(filter.doFilter(context(Level.CRITICAL, Instant.now())).isPass()).isTrue();
    }

    @Test
    @DisplayName("免打扰：22:00-09:00 内延后到次日 09:00，CRITICAL 直通")
    void shouldDeferDuringQuietHours() {
        QuietHoursFilter filter = new QuietHoursFilter(
                LocalTime.of(22, 0), LocalTime.of(9, 0), UTC, true, true);
        Instant at2330 = Instant.parse("2026-10-22T23:30:00Z");

        FilterVerdict verdict = filter.doFilter(context(Level.WARN, at2330));
        assertThat(verdict.decision()).isEqualTo(FilterVerdict.Decision.DEFER);
        assertThat(verdict.deferUntil()).isEqualTo(Instant.parse("2026-10-23T09:00:00Z"));
        assertThat(verdict.reason()).isEqualTo("quiet-hours-defer");

        assertThat(filter.doFilter(context(Level.CRITICAL, at2330)).isPass()).isTrue();
        assertThat(filter.order()).isEqualTo(QuietHoursFilter.ORDER);
        assertThat(filter.name()).isEqualTo("quiet-hours");
    }

    @Test
    @DisplayName("免打扰：defer=false 时直接丢弃")
    void shouldDropWhenDeferDisabled() {
        QuietHoursFilter filter = new QuietHoursFilter(
                LocalTime.of(22, 0), LocalTime.of(9, 0), UTC, false, true);

        FilterVerdict verdict = filter.doFilter(context(Level.ERROR, Instant.parse("2026-10-22T23:30:00Z")));
        assertThat(verdict.decision()).isEqualTo(FilterVerdict.Decision.REJECT);
        assertThat(verdict.reason()).isEqualTo("quiet-hours-drop");
    }

    @Test
    @DisplayName("免打扰：时段外放行；from==to 视为未配置")
    void shouldPassOutsideQuietHours() {
        QuietHoursFilter filter = new QuietHoursFilter(
                LocalTime.of(22, 0), LocalTime.of(9, 0), UTC, true, true);
        assertThat(filter.doFilter(context(Level.ERROR, Instant.parse("2026-10-22T12:00:00Z"))).isPass()).isTrue();

        QuietHoursFilter disabled = new QuietHoursFilter(
                LocalTime.of(9, 0), LocalTime.of(9, 0), UTC, true, true);
        assertThat(disabled.doFilter(context(Level.ERROR, Instant.parse("2026-10-22T09:00:00Z"))).isPass()).isTrue();
    }

    private static FilterContext context(Level level, Instant now) {
        NotifyEvent event = NotifyEvent.builder("TEST_SCENE").level(level).build();
        SceneConfig scene = SceneConfig.builder("TEST_SCENE").enabled(true).build();
        return new FilterContext(event, scene, now, "dedupe-key", Duration.ofMinutes(1));
    }
}
