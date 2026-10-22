package io.github.pkyit.notifyx.bus.rabbitmq;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.rabbitmq.client.ConnectionFactory;

import io.github.pkyit.notifyx.core.spi.NotifyBus;

/**
 * RabbitMQ 总线自动装配：仅 {@code mode=mq} + {@code notifyx.bus=rabbitmq} 时生效。
 *
 * @since 1.0
 */
@AutoConfiguration(beforeName = "io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration")
@ConditionalOnClass(ConnectionFactory.class)
@ConditionalOnProperty(prefix = "notifyx", name = "mode", havingValue = "mq")
@ConditionalOnProperty(prefix = "notifyx", name = "bus", havingValue = "rabbitmq")
@EnableConfigurationProperties(RabbitMqBusProperties.class)
public class RabbitMqBusAutoConfiguration {

    /**
     * RabbitMQ 总线（懒连接）。
     *
     * @param properties 配置
     * @return 总线
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(NotifyBus.class)
    public RabbitMqNotifyBus rabbitMqNotifyBus(RabbitMqBusProperties properties) {
        return new RabbitMqNotifyBus(properties);
    }
}
