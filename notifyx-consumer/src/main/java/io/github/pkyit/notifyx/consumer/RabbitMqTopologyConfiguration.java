package io.github.pkyit.notifyx.consumer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消费拓扑声明（transport=rabbitmq）：与 SDK producer 侧 {@code RabbitMqNotifyBus}
 * 声明等价的 durable 主队列；开启退避重试时声明死信交换 + 多级 TTL 重试队列与绑定（设计书 §11.2）。
 *
 * <p>这里声明与 producer 侧一字不差，避免 {@code @RabbitListener} 自动声明时因参数不一致而
 * 触发 {@code PRECONDITION_FAILED}。</p>
 *
 * @since 1.0.1
 */
@Configuration
@ConditionalOnProperty(prefix = "notifyx.consumer", name = "transport", havingValue = "rabbitmq")
public class RabbitMqTopologyConfiguration {

    /** 死信队列的 routing key（与 producer 侧一致，重试耗尽后消息落此队列）。 */
    static final String DEAD_ROUTING_KEY = "dead";

    /** 固定三级退避（与 producer 侧一致）。 */
    private static final List<RetryLevel> RETRY_LEVELS = List.of(
            new RetryLevel("5s", 5_000L),
            new RetryLevel("1m", 60_000L),
            new RetryLevel("5m", 300_000L));

    /**
     * 主队列（开启重试时挂死信交换与死信 routing key），供 {@code @RabbitListener} 引用。
     *
     * @param properties 配置
     * @return 主队列
     */
    @Bean("notifyxRabbitMainQueue")
    public Queue rabbitMainQueue(ConsumerProperties properties) {
        QueueBuilder builder = QueueBuilder.durable(properties.getRabbitmq().getQueue());
        if (properties.getRabbitmq().isRetryEnabled()) {
            builder.deadLetterExchange(properties.getRabbitmq().getRetryExchange())
                    .deadLetterRoutingKey(DEAD_ROUTING_KEY);
        }
        return builder.build();
    }

    /**
     * 死信交换 + 多级 TTL 重试队列与绑定 + durable 死信队列（仅重试开启时声明）。
     *
     * @param properties 配置
     * @return 声明集合
     */
    @Bean
    @ConditionalOnProperty(prefix = "notifyx.consumer.rabbitmq", name = "retry-enabled", havingValue = "true")
    public Declarables rabbitRetryTopology(ConsumerProperties properties) {
        String exchange = properties.getRabbitmq().getRetryExchange();
        String mainQueue = properties.getRabbitmq().getQueue();
        List<Declarable> declarables = new ArrayList<>();
        DirectExchange directExchange = new DirectExchange(exchange, true, false);
        declarables.add(directExchange);
        for (RetryLevel level : RETRY_LEVELS) {
            Queue waitQueue = QueueBuilder.durable(exchange + "." + level.label())
                    .ttl((int) level.ttlMillis())
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(mainQueue)
                    .build();
            declarables.add(waitQueue);
            declarables.add(BindingBuilder.bind(waitQueue).to(directExchange).with(level.label()));
        }
        Queue deadQueue = new Queue(exchange + ".dead", true, false, false);
        declarables.add(deadQueue);
        declarables.add(BindingBuilder.bind(deadQueue).to(directExchange).with(DEAD_ROUTING_KEY));
        return new Declarables(declarables);
    }

    record RetryLevel(String label, long ttlMillis) {
    }
}
