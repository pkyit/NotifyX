package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.exception.BusException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyBus;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * MQ 模式门面测试（§11.3 producer-only）：发布报文、失败落盘、回调警告不阻断。
 *
 * @since 1.0
 */
class MqNotifyKitTest {

    private final List<NotifyEnvelope> published = new CopyOnWriteArrayList<>();
    private final RecordingFailureStore store = new RecordingFailureStore();

    @Test
    @DisplayName("发送事件：发布 NotifyEnvelope，idempotencyKey=eventId、key=dedupeKey")
    void shouldPublishEnvelope() {
        MqNotifyKit kit = new MqNotifyKit(bus(false), store, null, "inst-1");

        kit.send(NotifyEvent.builder("SCENE_A").level(Level.ERROR).bizId("B1").param("k", "v").build());

        assertThat(published).hasSize(1);
        NotifyEnvelope envelope = published.get(0);
        assertThat(envelope.scene()).isEqualTo("SCENE_A");
        assertThat(envelope.idempotencyKey()).isEqualTo(envelope.eventId());
        assertThat(envelope.dedupeKey()).startsWith("sha256:");
        assertThat(envelope.instanceId()).isEqualTo("inst-1");
    }

    @Test
    @DisplayName("发布失败：写 FailedRecord 兜底，不向业务抛异常")
    void shouldPersistOnBusFailure() {
        MqNotifyKit kit = new MqNotifyKit(bus(true), store, null, "inst-1");
        NotifyEvent event = NotifyEvent.builder("SCENE_A").level(Level.ERROR).build();

        kit.send(event);

        assertThat(store.records).hasSize(1);
        assertThat(store.records.get(0).status()).isEqualTo(RecordStatus.PENDING);
        assertThat(store.records.get(0).channel()).isEqualTo("fake");
        assertThat(store.records.get(0).envelopeJson()).contains("SCENE_A");
    }

    @Test
    @DisplayName("alarm 构造 ERROR 事件并经总线发布")
    void shouldPublishAlarm() {
        MqNotifyKit kit = new MqNotifyKit(bus(false), store, null, "inst-1");

        kit.alarm("ORDER_FAIL", new IllegalStateException("boom"), Map.of("orderId", "B1"));

        assertThat(published).hasSize(1);
        NotifyEnvelope envelope = published.get(0);
        assertThat(envelope.scene()).isEqualTo("ORDER_FAIL");
        assertThat(envelope.level()).isEqualTo(Level.ERROR);
        assertThat(String.valueOf(envelope.params().get("exception"))).isEqualTo("IllegalStateException");
    }

    private NotifyBus bus(boolean fail) {
        return new NotifyBus() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public void publish(NotifyEnvelope envelope) {
                if (fail) {
                    throw new BusException("broker down");
                }
                published.add(envelope);
            }
        };
    }

    private static final class RecordingFailureStore implements FailureStore {

        private final List<FailedRecord> records = new ArrayList<>();

        @Override
        public void save(FailedRecord record) {
            records.add(record);
        }

        @Override
        public List<FailedRecord> loadPending(int limit) {
            return List.copyOf(records);
        }

        @Override
        public boolean updateStatus(String recordId, int expectedVersion, RecordStatus status, int attempts) {
            return false;
        }

        @Override
        public long pendingCount() {
            return records.size();
        }
    }
}
