package io.github.pkyit.notifyx.bus.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.spi.NotifyBus;

/**
 * RabbitMQ 自动装配测试：仅在 mode=mq + bus=rabbitmq 时创建（懒连接，不依赖 broker）。
 *
 * @since 1.0
 */
class RabbitMqBusAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitMqBusAutoConfiguration.class));

    @Test
    @DisplayName("mq + rabbitmq：装配 RabbitMqNotifyBus")
    void shouldCreateBus() {
        runner.withPropertyValues("notifyx.mode=mq", "notifyx.bus=rabbitmq",
                        "notifyx.bus.rabbitmq.queue=notifyx-events")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotifyBus.class);
                    assertThat(context.getBean(NotifyBus.class)).isInstanceOf(RabbitMqNotifyBus.class);
                    assertThat(context.getBean(NotifyBus.class).name()).isEqualTo("rabbitmq");
                });
    }

    @Test
    @DisplayName("local 或 bus=kafka 时不装配")
    void shouldBackOff() {
        runner.withPropertyValues("notifyx.mode=local").run(context ->
                assertThat(context).doesNotHaveBean(NotifyBus.class));
        runner.withPropertyValues("notifyx.mode=mq", "notifyx.bus=kafka").run(context ->
                assertThat(context).doesNotHaveBean(NotifyBus.class));
    }
}
