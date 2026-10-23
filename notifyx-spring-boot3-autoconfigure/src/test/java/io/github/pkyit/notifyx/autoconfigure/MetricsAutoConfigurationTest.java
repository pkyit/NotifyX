package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 指标自动装配测试：{@code notifyx.metrics.enabled} 与 {@code notifyx.metrics.prefix} 必须真实生效。
 *
 * @since 1.0
 */
class MetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxMetricsAutoConfiguration.class,
                    NotifyxAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("默认装配 Micrometer 指标，前缀 notifyx")
    void shouldUseDefaultPrefix() {
        runner.run(context -> {
            assertThat(context.getBean(NotifyMetrics.class)).isInstanceOf(MicrometerNotifyMetrics.class);
            context.getBean(NotifyMetrics.class).eventReceived(event(), "L4");
            assertThat(context.getBean(SimpleMeterRegistry.class)
                    .get("notifyx.event.received").counter().count()).isEqualTo(1.0);
        });
    }

    @Test
    @DisplayName("notifyx.metrics.prefix 改变指标名前缀")
    void shouldApplyCustomPrefix() {
        runner.withPropertyValues("notifyx.metrics.prefix=acme")
                .run(context -> {
                    context.getBean(NotifyMetrics.class).eventReceived(event(), "L4");
                    assertThat(context.getBean(SimpleMeterRegistry.class)
                            .get("acme.event.received").counter().count()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("notifyx.metrics.enabled=false 时不装配 Micrometer，回退 no-op")
    void shouldBackOffWhenDisabled() {
        runner.withPropertyValues("notifyx.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("notifyxMicrometerMetrics");
                    assertThat(context.getBean(NotifyMetrics.class))
                            .isNotInstanceOf(MicrometerNotifyMetrics.class);
                });
    }

    private static NotifyEvent event() {
        return NotifyEvent.builder("S1").level(Level.WARN).build();
    }
}
