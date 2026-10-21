package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.channel.log.LogChannelAutoConfiguration;
import io.github.pkyit.notifyx.core.model.HealthStatus;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.InMemoryFailureStore;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.MemoryRateLimiter;

/**
 * 健康指示器测试（设计书 15.2）：DEGRADED/UP/DOWN 判定与默认不探活。
 *
 * @since 1.0
 */
class NotifyxHealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class,
                    LogChannelAutoConfiguration.class, NotifyxActuatorAutoConfiguration.class));

    @Test
    @DisplayName("默认装配（内存去重/限流）→ DEGRADED，K8s readiness 仍为 UP")
    void shouldReportDegradedForInMemoryStores() {
        runner.run(context -> {
            NotifyxHealthIndicator indicator = context.getBean(NotifyxHealthIndicator.class);
            Health health = indicator.health();

            assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
            assertThat(health.getDetails()).containsKey("degradedReason");
            assertThat(health.getDetails().get("channels")).asString().contains("log");
        });
    }

    @Test
    @DisplayName("全局静默 → DOWN")
    void shouldReportDownWhenMuted() {
        runner.withPropertyValues("notifyx.global-mute.enabled=true")
                .run(context -> assertThat(context.getBean(NotifyxHealthIndicator.class).health().getStatus())
                        .isEqualTo(Status.DOWN));
    }

    @Test
    @DisplayName("probe-enabled=true 时渠道 DOWN 计入 DEGRADED 详情")
    void shouldProbeChannelsWhenEnabled() {
        runner.withBean("downChannel", NotifyChannel.class, DownChannel::new)
                .withPropertyValues("notifyx.actuator.health.probe-enabled=true")
                .run(context -> {
                    NotifyxHealthIndicator indicator = context.getBean(NotifyxHealthIndicator.class);
                    Health health = indicator.health();
                    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
                    assertThat(health.getDetails().get("downChannels")).asString().contains("down");
                });
    }

    @Test
    @DisplayName("没有渠道 → DOWN")
    void shouldReportDownWithoutChannels() {
        NotifyxProperties properties = new NotifyxProperties();
        NotifyxHealthIndicator indicator = new NotifyxHealthIndicator(properties, List.of(),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("no NotifyChannel registered");
    }

    @Test
    @DisplayName("notifyx.disabled → OUT_OF_SERVICE")
    void shouldReportOutOfServiceWhenDisabled() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.setEnabled(false);
        NotifyxHealthIndicator indicator = new NotifyxHealthIndicator(properties, List.of(new UpChannel()),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("notifyx.actuator.health.enabled=false 时不装配健康指示器")
    void shouldBackOffWhenDisabled() {
        runner.withPropertyValues("notifyx.actuator.health.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NotifyxHealthIndicator.class));
    }

    private static final class DownChannel implements NotifyChannel {

        @Override
        public String name() {
            return "down";
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.DOWN;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            return SendResult.success("1", null, Duration.ZERO);
        }
    }

    private static final class UpChannel implements NotifyChannel {

        @Override
        public String name() {
            return "up";
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            return SendResult.success("1", null, Duration.ZERO);
        }
    }
}
