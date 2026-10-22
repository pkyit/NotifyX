package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * 消费者测试：解码→幂等→重建事件；重复消息只投递一次。
 *
 * @since 1.0
 */
class KafkaEnvelopeConsumerTest {

    private final RecordingKit kit = new RecordingKit();
    private final KafkaEnvelopeConsumer consumer = new KafkaEnvelopeConsumer(kit,
            new MemoryIdempotencyStore(60_000L, 100));

    @Test
    @DisplayName("首次消息解码后投递，事件字段还原")
    void shouldDispatchEnvelope() {
        consumer.onMessage(payload("evt_1", "idem-1"));

        assertThat(kit.events).hasSize(1);
        assertThat(kit.events.get(0).scene()).isEqualTo("SCENE_LIVE");
        assertThat(kit.events.get(0).bizId()).isEqualTo("B1");
    }

    @Test
    @DisplayName("同一 idempotencyKey 重复消息只投递一次")
    void shouldIgnoreDuplicate() {
        consumer.onMessage(payload("evt_1", "idem-1"));
        consumer.onMessage(payload("evt_2", "idem-1"));

        assertThat(kit.events).hasSize(1);
    }

    @Test
    @DisplayName("非法 JSON 只记录，不抛异常")
    void shouldSwallowBadPayload() {
        consumer.onMessage("{bad");

        assertThat(kit.events).isEmpty();
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
