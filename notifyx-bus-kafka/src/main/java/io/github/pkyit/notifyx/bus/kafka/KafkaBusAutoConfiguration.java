package io.github.pkyit.notifyx.bus.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.spi.NotifyBus;

/**
 * Kafka 总线自动装配：仅 {@code notifyx.mode=mq} 且 {@code notifyx.bus=kafka} 时生效。
 *
 * <p>排在 {@code NotifyxAutoConfiguration} 之前，保证 MQ 门面能注入到总线。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(beforeName = "io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration")
@ConditionalOnClass(KafkaProducer.class)
@ConditionalOnProperty(prefix = "notifyx", name = "mode", havingValue = "mq")
@ConditionalOnProperty(prefix = "notifyx", name = "bus", havingValue = "kafka")
@EnableConfigurationProperties(KafkaBusProperties.class)
public class KafkaBusAutoConfiguration {

    /**
     * Kafka producer 总线。
     *
     * @param properties 配置
     * @return 总线
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(NotifyBus.class)
    public KafkaNotifyBus kafkaNotifyBus(KafkaBusProperties properties) {
        return new KafkaNotifyBus(properties);
    }
}
