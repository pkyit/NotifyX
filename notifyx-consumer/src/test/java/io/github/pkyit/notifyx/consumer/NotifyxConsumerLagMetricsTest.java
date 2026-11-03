package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

import io.github.pkyit.notifyx.consumer.NotifyxConsumerLagMetrics.CachedLagSampler;
import io.github.pkyit.notifyx.consumer.NotifyxConsumerLagMetrics.RabbitMqLagSampler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@code notifyx.consumer.lag} 指标测试：注册、标签、采样降级与堆积计算。
 *
 * @since 1.0.1
 */
class NotifyxConsumerLagMetricsTest {

    @Test
    @DisplayName("注册后存在 notifyx.consumer.lag，标签与采样值正确")
    void shouldRegisterGaugeWithTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        NotifyxConsumerLagMetrics.register(registry, "rabbitmq", "notifyx-events", () -> 7L);

        Gauge gauge = registry.get(NotifyxConsumerLagMetrics.METRIC_NAME)
                .tags("transport", "rabbitmq", "queue", "notifyx-events").gauge();
        assertThat(gauge.value()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("采样抛异常时返回上次成功值且不向上抛")
    void shouldFallbackToLastKnownValue() {
        AtomicLong calls = new AtomicLong();
        CachedLagSampler sampler = new CachedLagSampler(() -> {
            if (calls.getAndIncrement() == 0) {
                return 42L;
            }
            throw new IllegalStateException("sampling boom");
        });

        assertThat(sampler.lag()).isEqualTo(42L);
        assertThatCode(sampler::lag).doesNotThrowAnyException();
        assertThat(sampler.lag()).isEqualTo(42L);
    }

    @Test
    @DisplayName("RabbitMQ 采样读取 QUEUE_MESSAGE_COUNT")
    void shouldReadRabbitMqMessageCount() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        Properties queueProperties = new Properties();
        queueProperties.put("QUEUE_MESSAGE_COUNT", 12);
        when(rabbitAdmin.getQueueProperties("notifyx-events")).thenReturn(queueProperties);

        RabbitMqLagSampler sampler = new RabbitMqLagSampler(rabbitAdmin, "notifyx-events");

        assertThat(sampler.lag()).isEqualTo(12L);
    }

    @Test
    @DisplayName("RabbitMQ 队列属性缺失时降级为上次值，未知时为 0")
    void shouldFallbackWhenRabbitMqQueuePropertiesMissing() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        Properties queueProperties = new Properties();
        queueProperties.put("QUEUE_MESSAGE_COUNT", 9);
        when(rabbitAdmin.getQueueProperties("notifyx-events"))
                .thenReturn(queueProperties)
                .thenReturn(null);

        RabbitMqLagSampler delegate = new RabbitMqLagSampler(rabbitAdmin, "notifyx-events");
        CachedLagSampler sampler = new CachedLagSampler(delegate::lag);
        assertThat(sampler.lag()).isEqualTo(9L);
        assertThat(sampler.lag()).isEqualTo(9L);

        CachedLagSampler unknown = new CachedLagSampler(delegate::lag);
        assertThat(unknown.lag()).isZero();
    }

    @Test
    @DisplayName("computeLag 逐分区求和，committed 缺失按 0")
    void shouldComputeLagAcrossPartitions() {
        TopicPartition p0 = new TopicPartition("notifyx-events", 0);
        TopicPartition p1 = new TopicPartition("notifyx-events", 1);
        Map<TopicPartition, Long> endOffsets = Map.of(p0, 100L, p1, 50L);
        Map<TopicPartition, OffsetAndMetadata> committed = Map.of(p0, new OffsetAndMetadata(40L));

        assertThat(NotifyxConsumerLagMetrics.computeLag(endOffsets, committed)).isEqualTo(110L);
    }

    @Test
    @DisplayName("committed 大于 end 时该分区计 0")
    void shouldClampNegativeLagToZero() {
        TopicPartition p0 = new TopicPartition("notifyx-events", 0);
        TopicPartition p1 = new TopicPartition("notifyx-events", 1);
        Map<TopicPartition, Long> endOffsets = Map.of(p0, 10L, p1, 20L);
        Map<TopicPartition, OffsetAndMetadata> committed = Map.of(p1, new OffsetAndMetadata(25L));

        assertThat(NotifyxConsumerLagMetrics.computeLag(endOffsets, committed)).isEqualTo(10L);
    }

    @Test
    @DisplayName("无 MeterRegistry 时初始化不抛异常")
    void shouldSkipWhenNoMeterRegistry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitAdmin> rabbitAdmins = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaProperties> kafkaProperties = mock(ObjectProvider.class);
        when(registries.getIfAvailable()).thenReturn(null);

        NotifyxConsumerLagMetrics metrics = new NotifyxConsumerLagMetrics(
                new ConsumerProperties(), registries, rabbitAdmins, kafkaProperties);

        assertThatCode(metrics::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(metrics::destroy).doesNotThrowAnyException();
    }
}
