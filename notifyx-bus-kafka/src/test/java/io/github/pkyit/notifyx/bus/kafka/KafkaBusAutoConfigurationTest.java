package io.github.pkyit.notifyx.bus.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.spi.NotifyBus;

/**
 * Kafka 自动装配测试：只在 mode=mq + bus=kafka 时创建。
 *
 * @since 1.0
 */
class KafkaBusAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaBusAutoConfiguration.class));

    @Test
    @DisplayName("mode=mq + bus=kafka：装配 KafkaNotifyBus")
    void shouldCreateBus() {
        runner.withPropertyValues("notifyx.mode=mq", "notifyx.bus=kafka",
                        "notifyx.bus.kafka.bootstrap-servers=localhost:1",
                        "notifyx.bus.kafka.topic=notifyx-events")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotifyBus.class);
                    assertThat(context.getBean(NotifyBus.class)).isInstanceOf(KafkaNotifyBus.class);
                    assertThat(context.getBean(NotifyBus.class).name()).isEqualTo("kafka");
                });
    }

    @Test
    @DisplayName("local 模式或未配置 bus 时不装配")
    void shouldBackOff() {
        runner.withPropertyValues("notifyx.mode=local").run(context ->
                assertThat(context).doesNotHaveBean(NotifyBus.class));
        runner.withPropertyValues("notifyx.mode=mq", "notifyx.bus=rabbitmq").run(context ->
                assertThat(context).doesNotHaveBean(NotifyBus.class));
    }
}
