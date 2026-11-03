package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

/**
 * RabbitMQ 消费拓扑声明测试：主队列死信参数与多级 TTL 重试队列/绑定。
 *
 * @since 1.0.1
 */
class RabbitMqTopologyConfigurationTest {

    private final RabbitMqTopologyConfiguration configuration = new RabbitMqTopologyConfiguration();

    @Test
    @DisplayName("重试关闭：主队列无死信交换")
    void shouldDeclarePlainMainQueue() {
        ConsumerProperties properties = new ConsumerProperties();

        Queue queue = configuration.rabbitMainQueue(properties);

        assertThat(queue.getName()).isEqualTo("notifyx-events");
        assertThat(queue.getArguments()).doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    @DisplayName("重试开启：主队列挂死信交换")
    void shouldDeclareMainQueueWithDlx() {
        ConsumerProperties properties = new ConsumerProperties();
        properties.getRabbitmq().setRetryEnabled(true);

        Queue queue = configuration.rabbitMainQueue(properties);

        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "notifyx.retry")
                .containsEntry("x-dead-letter-routing-key", "dead");
    }

    @Test
    @DisplayName("重试开启：声明 1 exchange + 4 队列 + 4 绑定，等待队列死信回主队列")
    void shouldDeclareRetryTopology() {
        ConsumerProperties properties = new ConsumerProperties();
        properties.getRabbitmq().setRetryEnabled(true);

        Declarables declarables = configuration.rabbitRetryTopology(properties);

        List<Declarable> list = List.copyOf(declarables.getDeclarables());
        assertThat(list.stream().filter(d -> d instanceof Exchange).count()).isEqualTo(1);
        assertThat(list.stream().filter(d -> d instanceof Queue).count()).isEqualTo(4);
        assertThat(list.stream().filter(d -> d instanceof Binding).count()).isEqualTo(4);

        Queue waitQueue = list.stream().filter(d -> d instanceof Queue)
                .map(d -> (Queue) d)
                .filter(q -> q.getName().equals("notifyx.retry.5s"))
                .findFirst().orElseThrow();
        assertThat(waitQueue.getArguments())
                .containsEntry("x-message-ttl", 5_000)
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "notifyx-events");

        assertThat(list.stream().filter(d -> d instanceof Queue)
                .map(d -> (Queue) d)
                .map(Queue::getName)
                .collect(Collectors.toList())).contains("notifyx.retry.dead");

        List<String> bindingKeys = list.stream().filter(d -> d instanceof Binding)
                .map(d -> ((Binding) d).getRoutingKey())
                .sorted()
                .collect(Collectors.toList());
        assertThat(bindingKeys).containsExactly("1m", "5m", "5s", "dead");
    }
}
