package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * RabbitMQ 消费者测试：解码 → 幂等 → 重建事件。
 *
 * @since 1.0
 */
class RabbitMqEnvelopeConsumerTest {

    private final RecordingKit kit = new RecordingKit();
    private final RabbitMqEnvelopeConsumer consumer = new RabbitMqEnvelopeConsumer(kit,
            new MemoryIdempotencyStore(60_000L, 100));

    @Test
    @DisplayName("首次消息投递，重复幂等键只投一次")
    void shouldDispatchOnce() {
        consumer.onMessage(message(payload("evt_1", "idem-rmq")));
        consumer.onMessage(message(payload("evt_2", "idem-rmq")));

        assertThat(kit.events).hasSize(1);
        assertThat(kit.events.get(0).scene()).isEqualTo("SCENE_LIVE");
    }

    @Test
    @DisplayName("坏 JSON 不抛异常")
    void shouldSwallowBadPayload() {
        consumer.onMessage(message("{bad"));

        assertThat(kit.events).isEmpty();
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

        @Override
        public void send(NotifyEvent event) {
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
