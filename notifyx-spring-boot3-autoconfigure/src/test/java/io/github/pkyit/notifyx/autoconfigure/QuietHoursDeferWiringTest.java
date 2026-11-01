package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.pipeline.DeferredHandler;
import io.github.pkyit.notifyx.core.pipeline.InMemoryDeferredHandler;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * 免打扰延后投递装配测试（设计书 §9.3）：
 * 开启免打扰且 defer=true 时，夜间事件进入延后队列而不是被丢弃或直接发出。
 *
 * @since 1.0.1
 */
class QuietHoursDeferWiringTest {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Test
    @DisplayName("免打扰开启且 defer=true：命中事件进入延后队列，不投递")
    void shouldDeferEventsDuringQuietHours() {
        AtomicInteger sends = new AtomicInteger();
        runner(sends, true).run(context -> {
            DeferredHandler handler = context.getBean(DeferredHandler.class);
            assertThat(handler).isInstanceOf(InMemoryDeferredHandler.class);

            context.getBean(NotifyKit.class)
                    .send(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());

            assertThat(((InMemoryDeferredHandler) handler).pending()).isEqualTo(1);
            assertThat(sends).hasValue(0);
        });
    }

    @Test
    @DisplayName("免打扰开启且 defer=false：命中事件直接丢弃，不进延后队列")
    void shouldDropEventsWhenDeferDisabled() {
        AtomicInteger sends = new AtomicInteger();
        runner(sends, false).run(context -> {
            context.getBean(NotifyKit.class)
                    .send(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());

            InMemoryDeferredHandler handler = context.getBean(InMemoryDeferredHandler.class);
            assertThat(handler.pending()).isZero();
            assertThat(sends).hasValue(0);
        });
    }

    @Test
    @DisplayName("CRITICAL 直通免打扰，照常投递")
    void shouldLetCriticalBypassQuietHours() {
        AtomicInteger sends = new AtomicInteger();
        runner(sends, true).run(context -> {
            context.getBean(NotifyKit.class)
                    .send(NotifyEvent.builder("SCENE_A").level(Level.CRITICAL).build());

            assertThat(sends).hasValue(1);
        });
    }

    @Test
    @DisplayName("免打扰关闭时不装配 DeferredHandler")
    void shouldNotCreateHandlerWhenQuietHoursDisabled() {
        runner(new AtomicInteger(), true, false).run(context -> {
            assertThat(context.getBeanNamesForType(DeferredHandler.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("延后事件到期经有界执行器重投到管线（端到端）")
    void shouldRedeliverDueEventThroughTaskExecutor() throws InterruptedException {
        AtomicInteger sends = new AtomicInteger();
        // from==to 视为未配置免打扰窗口，但延后投递器仍随 quiet-hours.enabled 装配，
        // 用于验证自动装配的 sink → NotifyTaskExecutor → 管线 → 渠道 全链路
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class))
                .withBean("recording", NotifyChannel.class, () -> recordingChannel(sends))
                .withPropertyValues(
                        "notifyx.scenes.SCENE_A.channels=recording",
                        "notifyx.quiet-hours.enabled=true",
                        "notifyx.quiet-hours.from=00:00",
                        "notifyx.quiet-hours.to=00:00",
                        "notifyx.quiet-hours.timezone=UTC");
        runner.run(context -> {
            InMemoryDeferredHandler handler = context.getBean(InMemoryDeferredHandler.class);
            NotifyEvent event = NotifyEvent.builder("SCENE_A").level(Level.WARN).build();
            handler.defer(event, Instant.now().plusMillis(150));

            long deadline = System.currentTimeMillis() + 5_000;
            while (sends.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            assertThat(sends).hasValue(1);
            assertThat(handler.pending()).isZero();
        });
    }

    private static ApplicationContextRunner runner(AtomicInteger sends, boolean defer) {
        return runner(sends, defer, true);
    }

    private static ApplicationContextRunner runner(AtomicInteger sends, boolean defer, boolean quietHoursEnabled) {
        // from=now-1h、to=now+1h（UTC），保证当前时刻必然落在免打扰窗口内
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class))
                .withBean("recording", NotifyChannel.class, () -> recordingChannel(sends))
                .withPropertyValues(
                        "notifyx.async.enabled=false",
                        "notifyx.scenes.SCENE_A.channels=recording",
                        "notifyx.scenes.SCENE_A.level=ERROR",
                        "notifyx.quiet-hours.enabled=" + quietHoursEnabled,
                        "notifyx.quiet-hours.from=" + now.minusHours(1).format(HH_MM),
                        "notifyx.quiet-hours.to=" + now.plusHours(1).format(HH_MM),
                        "notifyx.quiet-hours.timezone=UTC",
                        "notifyx.quiet-hours.defer=" + defer,
                        "notifyx.quiet-hours.max-deferred=100");
    }

    private static NotifyChannel recordingChannel(AtomicInteger sends) {
        return new NotifyChannel() {
            @Override
            public String name() {
                return "recording";
            }

            @Override
            public SendResult send(NotifyContext ctx) {
                sends.incrementAndGet();
                return SendResult.success("1", null, Duration.ZERO);
            }
        };
    }
}
