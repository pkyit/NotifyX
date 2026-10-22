package io.github.pkyit.notifyx.consumer;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MQ 模式独立消费服务（设计书 §11.4）。
 *
 * <p>单实例起步：消费 Kafka/RabbitMQ → 幂等检查 → 完整责任链 → 渠道发送 → ACK。</p>
 *
 * @since 1.0
 */
@SpringBootApplication
@EnableRabbit
public class NotifyxConsumerApplication {

    /**
     * 启动入口。
     *
     * @param args 参数
     */
    public static void main(String[] args) {
        SpringApplication.run(NotifyxConsumerApplication.class, args);
    }
}
