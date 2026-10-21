package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.pipeline.filter.DedupeFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.GlobalRateLimitFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.SceneRoutingFilter;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.MemoryRateLimiter;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 责任链管线测试（对应设计书 18.2 的 T4/T5/T6）。
 *
 * @since 1.0
 */
class NotifyPipelineTest {

    private RecordingChannel channel;
    private MemoryDedupeStore dedupeStore;
    private RecordingAudit audit;

    @BeforeEach
    void setUp() {
        channel = new RecordingChannel();
        dedupeStore = new MemoryDedupeStore();
        audit = new RecordingAudit();
    }

    @Test
    @DisplayName("T6：CRITICAL 直通全局限流")
    void criticalBypassesGlobalRateLimit() {
        NotifyPipeline pipeline = pipeline(List.of(new GlobalRateLimitFilter(new MemoryRateLimiter(), 1, Duration.ofMinutes(1))),
                SceneConfig.builder("SCENE_A").channels(List.of("recording")).build());

        pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.CRITICAL).build());
        pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.CRITICAL).build());

        assertThat(channel.receivers).hasSize(2);
    }

    @Test
    @DisplayName("T5：去重拦截，同级事件窗口内只发一次")
    void dedupeSuppressesDuplicates() {
        NotifyPipeline pipeline = pipeline(List.of(new DedupeFilter(dedupeStore)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording")).silentWindow(Duration.ofMinutes(5)).build());

        NotifyEvent event = NotifyEvent.builder("SCENE_A").bizId("SO1").build();
        pipeline.handle(event);
        pipeline.handle(event);

        assertThat(channel.receivers).hasSize(1);
    }

    @Test
    @DisplayName("T4：发送失败后释放去重槽位，下一次同指纹可再次触发")
    void releasesDedupeOnFailure() {
        channel.failFirst.set(true);
        NotifyPipeline pipeline = pipeline(List.of(new DedupeFilter(dedupeStore)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording"))
                        .silentWindow(Duration.ofMinutes(5)).retry(1).build());

        NotifyEvent event = NotifyEvent.builder("SCENE_A").bizId("SO1").build();
        pipeline.handle(event);
        pipeline.handle(event);

        assertThat(channel.attempts.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("场景禁用时不投递")
    void disabledSceneIsSuppressed() {
        NotifyPipeline pipeline = pipeline(List.of(new SceneRoutingFilter()),
                SceneConfig.builder("SCENE_A").enabled(false).channels(List.of("recording")).build());

        pipeline.handle(NotifyEvent.builder("SCENE_A").build());

        assertThat(channel.receivers).isEmpty();
        assertThat(audit.records).anyMatch(record -> "SUPPRESSED".equals(record.status()));
    }

    private NotifyPipeline pipeline(List<EventFilter> filters, SceneConfig sceneConfig) {
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of(sceneConfig.scene(), sceneConfig), SceneRegistry.emptyDefaults()))
                .filters(filters)
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "[${level}] ${scene}", "${content}")))
                .dedupe(dedupeStore)
                .auditRecorder(audit)
                .build();
    }

    private static final class RecordingChannel implements NotifyChannel {

        private final List<String> receivers = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean failFirst = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            attempts.incrementAndGet();
            if (failFirst.compareAndSet(true, false)) {
                throw new io.github.pkyit.notifyx.core.exception.ChannelTimeoutException("recording", null);
            }
            receivers.add(ctx.receiver());
            return SendResult.success("1", null, Duration.ZERO);
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }
}