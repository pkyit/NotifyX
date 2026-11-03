package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * RabbitMQ 消费者测试：解码 → 幂等 → 重建事件，以及死信重试 / 拒绝语义。
 *
 * @since 1.0
 */
class RabbitMqEnvelopeConsumerTest {

    private final RecordingKit kit = new RecordingKit();
    private final MemoryIdempotencyStore store = new MemoryIdempotencyStore(60_000L, 100);
    private final Channel channel = mock(Channel.class);

    private RabbitMqEnvelopeConsumer consumer(boolean retryEnabled) {
        ConsumerProperties properties = new ConsumerProperties();
        properties.getRabbitmq().setRetryEnabled(retryEnabled);
        return new RabbitMqEnvelopeConsumer(kit, store, properties);
    }

    @Test
    @DisplayName("首次消息投递，重复幂等键只投一次")
    void shouldDispatchOnce() {
        RabbitMqEnvelopeConsumer consumer = consumer(false);
        consumer.onMessage(message(payload("evt_1", "idem-rmq")), channel);
        consumer.onMessage(message(payload("evt_2", "idem-rmq")), channel);

        assertThat(kit.events).hasSize(1);
        assertThat(kit.events.get(0).scene()).isEqualTo("SCENE_LIVE");
    }

    @Test
    @DisplayName("坏 JSON 不抛异常")
    void shouldSwallowBadPayload() {
        consumer(false).onMessage(message("{bad"), channel);

        assertThat(kit.events).isEmpty();
    }

    @Test
    @DisplayName("重试关闭：派发失败抛 AmqpRejectAndDontRequeueException")
    void shouldRejectOnFailureWhenRetryDisabled() {
        kit.fail = true;

        assertThatThrownBy(() -> consumer(false).onMessage(message(payload("evt_1", "idem-rmq")), channel))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    @DisplayName("重试开启：失败释放幂等键并重投到 retry.5s，保留消息头与 messageId")
    void shouldRepublishForRetry() throws Exception {
        kit.fail = true;
        Message inbound = message(payload("evt_1", "idem-rmq"));
        inbound.getMessageProperties().setMessageId("sha256:rmq");
        inbound.getMessageProperties().setHeader("x-custom", "keep");

        consumer(true).onMessage(inbound, channel);

        ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        verify(channel).basicPublish(eq("notifyx.retry"), eq("5s"), props.capture(), any(byte[].class));
        assertThat(props.getValue().getMessageId()).isEqualTo("sha256:rmq");
        assertThat(props.getValue().getHeaders())
                .containsEntry("x-custom", "keep")
                .containsEntry(RabbitMqEnvelopeConsumer.RETRY_COUNT_HEADER, 1);
        assertThat(store.firstSeen("idem-rmq")).isTrue();
    }

    @Test
    @DisplayName("超过重试上限：抛拒绝（进入死信）")
    void shouldGiveUpAfterMaxRetries() {
        kit.fail = true;
        Message message = message(payload("evt_1", "idem-rmq"));
        message.getMessageProperties().setHeader(RabbitMqEnvelopeConsumer.RETRY_COUNT_HEADER, 3);

        assertThatThrownBy(() -> consumer(true).onMessage(message, channel))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private static String payload(String eventId, String idempotencyKey) {
        NotifyEvent event = NotifyEvent.builder("SCENE_LIVE").eventId(eventId).level(Level.ERROR)
                .bizId("B1").build();
        return NotifyEnvelopeCodec.toJson(NotifyEnvelope.of(event, idempotencyKey, "sha256:x",
                null, null, "inst", 0, 1L));
    }

    private static final class RecordingKit implements NotifyKit {

        private final List<NotifyEvent> events = new CopyOnWriteArrayList<>();
        private boolean fail;

        @Override
        public void send(NotifyEvent event) {
            if (fail) {
                throw new IllegalStateException("dispatch failed");
            }
            events.add(event);
        }

        @Override
        public void send(String scene, Map<String, ?> params) {
            send(NotifyEvent.builder(scene).params(params).build());
        }

        @Override
        public void send(String scene, String receiver, Map<String, ?> params) {
            send(NotifyEvent.builder(scene).receiver(receiver).params(params).build());
        }

        @Override
        public void alarm(Throwable throwable) {
            send(NotifyEvent.builder("UNCAUGHT_EXCEPTION").build());
        }

        @Override
        public void alarm(String scene, Throwable throwable, Object... context) {
            send(NotifyEvent.builder(scene).build());
        }

        @Override
        public void sendAsync(NotifyEvent event) {
            send(event);
        }
    }
}
