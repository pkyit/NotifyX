package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.RecordStatus;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * 失败自动补偿测试（设计书 §9.9）：成功 → SENT、失败退避 → PENDING、达到上限 → DEAD、
 * 无法解码 → DEAD、CAS 失败跳过、关闭幂等。
 *
 * @since 1.0
 */
class FailureCompensationRunnerTest {

    private final InMemoryFailureStore store = new InMemoryFailureStore();
    private final RecordingKit kit = new RecordingKit();
    private final FailureCompensationRunner.Config config = new FailureCompensationRunner.Config(
            false, Duration.ofSeconds(60), 50, 3, Duration.ZERO);

    @Test
    @DisplayName("补偿成功：记录变 SENT，事件被重投")
    void shouldMarkSentOnSuccess() {
        store.save(record("evt_1", 0, 0, "SCENE_A"));
        FailureCompensationRunner runner = new FailureCompensationRunner(kit, store, config);

        int processed = runner.runOnce();
        runner.close();

        assertThat(processed).isEqualTo(1);
        assertThat(kit.events).hasSize(1);
        assertThat(kit.events.get(0).scene()).isEqualTo("SCENE_A");
        assertThat(store.loadPending(10)).isEmpty();
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    @DisplayName("补偿失败：未达上限重排 PENDING 并累加 attempts，达到上限转 DEAD")
    void shouldRequeueThenDead() {
        store.save(record("evt_2", 0, 0, "SCENE_A"));
        kit.failure = new IllegalStateException("down");
        FailureCompensationRunner runner = new FailureCompensationRunner(kit, store, config);

        runner.runOnce();
        FailedRecord afterFirst = store.all().get(0);
        assertThat(afterFirst.status()).isEqualTo(RecordStatus.PENDING);
        assertThat(afterFirst.attempts()).isEqualTo(1);
        assertThat(afterFirst.version()).isEqualTo(2);

        runner.runOnce();
        FailedRecord afterSecond = store.all().get(0);
        assertThat(afterSecond.status()).isEqualTo(RecordStatus.PENDING);
        assertThat(afterSecond.attempts()).isEqualTo(2);

        runner.runOnce();
        runner.close();
        assertThat(store.all().get(0).status()).isEqualTo(RecordStatus.DEAD);
        assertThat(store.all().get(0).attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("envelope 无法解码：直接 DEAD，不尝试投递")
    void shouldMarkDeadWhenEnvelopeBroken() {
        store.save(new FailedRecord("evt_3", RecordStatus.PENDING, 0, "{not-json", "log", "boom", 0,
                System.currentTimeMillis(), 0L));
        FailureCompensationRunner runner = new FailureCompensationRunner(kit, store, config);

        runner.runOnce();
        runner.close();

        assertThat(kit.events).isEmpty();
        assertThat(store.all().get(0).status()).isEqualTo(RecordStatus.DEAD);
    }

    @Test
    @DisplayName("CAS 抢锁失败：不投递、不改变状态")
    void shouldSkipWhenCasFails() {
        store.save(record("evt_4", 0, 0, "SCENE_A"));
        store.failUpdates = true;
        FailureCompensationRunner runner = new FailureCompensationRunner(kit, store, config);

        int processed = runner.runOnce();
        runner.close();

        assertThat(processed).isZero();
        assertThat(kit.events).isEmpty();
    }

    @Test
    @DisplayName("enabled=false 不启动调度，close 幂等")
    void shouldNotScheduleWhenDisabled() {
        FailureCompensationRunner runner = new FailureCompensationRunner(kit, store, config);
        assertThat(runner.isRunning()).isFalse();

        runner.close();
        runner.close();
    }

    @Test
    @DisplayName("与真实 DefaultNotifyKit 集成：重投经过完整管线并落渠道")
    void shouldDeliverThroughRealPipeline() {
        List<NotifyEvent> delivered = new ArrayList<>();
        io.github.pkyit.notifyx.core.spi.NotifyChannel channel =
                new io.github.pkyit.notifyx.core.spi.NotifyChannel() {
                    @Override
                    public String name() {
                        return "capture";
                    }

                    @Override
                    public io.github.pkyit.notifyx.core.model.SendResult send(
                            io.github.pkyit.notifyx.core.spi.NotifyContext ctx) {
                        delivered.add(ctx.event());
                        return io.github.pkyit.notifyx.core.model.SendResult.success("1", null, Duration.ZERO);
                    }
                };
        io.github.pkyit.notifyx.core.pipeline.SceneConfig scene =
                io.github.pkyit.notifyx.core.pipeline.SceneConfig.builder("SCENE_A")
                        .channels(List.of("capture"))
                        .build();
        io.github.pkyit.notifyx.core.pipeline.NotifyPipeline pipeline =
                io.github.pkyit.notifyx.core.pipeline.NotifyPipeline.builder()
                        .sceneRegistry(io.github.pkyit.notifyx.core.pipeline.SceneRegistry.of(
                                Map.of("SCENE_A", scene),
                                io.github.pkyit.notifyx.core.pipeline.SceneRegistry.emptyDefaults()))
                        .channelRegistry(io.github.pkyit.notifyx.core.pipeline.ChannelRegistry.of(List.of(channel)))
                        .templateEngine(new io.github.pkyit.notifyx.core.support.SimpleTemplateEngine())
                        .templates(Map.of("default", new io.github.pkyit.notifyx.core.pipeline.MessageTemplate(
                                "default", "t", "${content}")))
                        .build();
        io.github.pkyit.notifyx.core.pipeline.DefaultNotifyKit realKit =
                new io.github.pkyit.notifyx.core.pipeline.DefaultNotifyKit(pipeline, null);
        store.save(record("evt_5", 0, 0, "SCENE_A"));
        FailureCompensationRunner runner = new FailureCompensationRunner(realKit, store, config);

        runner.runOnce();
        runner.close();

        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).scene()).isEqualTo("SCENE_A");
        assertThat(store.all().get(0).status()).isEqualTo(RecordStatus.SENT);
    }

    private FailedRecord record(String id, int version, int attempts, String scene) {
        NotifyEvent event = NotifyEvent.builder(scene).level(Level.ERROR).bizId(id).build();
        String json = NotifyEnvelopeCodec.toJson(NotifyEnvelope.of(event, "key-" + id, "t", "s", "i", attempts, 1L));
        return new FailedRecord(id, RecordStatus.PENDING, version, json, "log", "down", attempts, 1L, 0L);
    }

    private static final class RecordingKit implements NotifyKit {

        private final List<NotifyEvent> events = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public void send(NotifyEvent event) {
            if (failure != null) {
                throw failure;
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

    private static final class InMemoryFailureStore implements FailureStore {

        private final Map<String, FailedRecord> records = new LinkedHashMap<>();
        private boolean failUpdates;

        @Override
        public synchronized void save(FailedRecord record) {
            records.put(record.recordId(), record);
        }

        @Override
        public synchronized List<FailedRecord> loadPending(int limit) {
            long now = System.currentTimeMillis();
            return records.values().stream()
                    .filter(record -> record.status() == RecordStatus.PENDING && record.nextRetryAt() <= now)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized boolean updateStatus(String recordId, int expectedVersion, RecordStatus status,
                                                 int attempts) {
            FailedRecord current = records.get(recordId);
            if (failUpdates || current == null || current.version() != expectedVersion) {
                return false;
            }
            records.put(recordId, new FailedRecord(recordId, status, current.version() + 1, current.envelopeJson(),
                    current.channel(), current.failureReason(), attempts, current.createdAt(), current.nextRetryAt()));
            return true;
        }

        @Override
        public synchronized boolean requeue(String recordId, int expectedVersion, int attempts, long nextRetryAt) {
            FailedRecord current = records.get(recordId);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            records.put(recordId, new FailedRecord(recordId, RecordStatus.PENDING, current.version() + 1,
                    current.envelopeJson(), current.channel(), current.failureReason(), attempts,
                    current.createdAt(), nextRetryAt));
            return true;
        }

        @Override
        public synchronized long pendingCount() {
            return records.values().stream().filter(record -> record.status() == RecordStatus.PENDING).count();
        }

        List<FailedRecord> all() {
            return List.copyOf(records.values());
        }
    }
}
