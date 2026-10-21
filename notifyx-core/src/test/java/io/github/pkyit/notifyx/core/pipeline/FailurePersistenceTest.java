package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.exception.ChannelTimeoutException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 失败落盘报文测试（设计书 §9.9 / §6.5）：失败时写入可还原的 NotifyEnvelope。
 *
 * @since 1.0
 */
class FailurePersistenceTest {

    private final RecordingFailureStore store = new RecordingFailureStore();

    @Test
    @DisplayName("投递失败时落盘的 envelope 可还原出原事件")
    void shouldPersistDecodableEnvelope() {
        NotifyPipeline pipeline = NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of("SCENE_A",
                        SceneConfig.builder("SCENE_A").channels(List.of("failing")).build()),
                        SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(new FailingChannel())))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .failureStore(store)
                .retryPolicy(new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .build();

        pipeline.handle(NotifyEvent.builder("SCENE_A")
                .level(Level.ERROR)
                .bizId("B1")
                .param("reason", "库存不足")
                .build());

        assertThat(store.records).hasSize(1);
        FailedRecord record = store.records.get(0);
        assertThat(record.status().name()).isEqualTo("PENDING");
        NotifyEnvelope envelope = NotifyEnvelopeCodec.fromJson(record.envelopeJson());
        assertThat(envelope.scene()).isEqualTo("SCENE_A");
        assertThat(envelope.bizId()).isEqualTo("B1");
        assertThat(envelope.params()).containsEntry("reason", "库存不足");
        assertThat(envelope.attempts()).isEqualTo(1);
        assertThat(envelope.toEvent().scene()).isEqualTo("SCENE_A");
    }

    private static final class RecordingFailureStore implements FailureStore {

        private final List<FailedRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void save(FailedRecord record) {
            records.add(record);
        }

        @Override
        public List<FailedRecord> loadPending(int limit) {
            return List.copyOf(records);
        }

        @Override
        public boolean updateStatus(String recordId, int expectedVersion, io.github.pkyit.notifyx.core.spi.RecordStatus status,
                                    int attempts) {
            return false;
        }

        @Override
        public long pendingCount() {
            return records.size();
        }
    }

    private static final class FailingChannel implements NotifyChannel {

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public int maxRetries() {
            return 0;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            throw new ChannelTimeoutException("failing", null);
        }
    }
}
