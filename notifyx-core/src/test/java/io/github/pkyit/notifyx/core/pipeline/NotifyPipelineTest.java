package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import io.github.pkyit.notifyx.core.pipeline.filter.QuietHoursFilter;
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
    @DisplayName("补偿重投（replay）跳过去重，同指纹在窗口内仍投递")
    void replayBypassesDedupe() {
        NotifyPipeline pipeline = pipeline(List.of(new DedupeFilter(dedupeStore)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording")).silentWindow(Duration.ofMinutes(5)).build());

        pipeline.handle(NotifyEvent.builder("SCENE_A").bizId("SO1").build());
        pipeline.handle(NotifyEvent.builder("SCENE_A").bizId("SO1").replay(true).build());

        assertThat(channel.receivers).hasSize(2);
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
    @DisplayName("数组参数归一后去重指纹稳定，窗口内只发一次")
    void arrayParamsDoNotBreakDedupe() {
        NotifyPipeline pipeline = pipeline(List.of(new DedupeFilter(dedupeStore)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording"))
                        .silentWindow(Duration.ofMinutes(5)).build());

        pipeline.handle(NotifyEvent.builder("SCENE_A").bizId("SO1")
                .param("args", new Object[]{"SO1", "boom"}).build());
        pipeline.handle(NotifyEvent.builder("SCENE_A").bizId("SO1")
                .param("args", new Object[]{"SO1", "boom"}).build());

        assertThat(channel.receivers).hasSize(1);
    }

    @Test
    @DisplayName("场景禁用时不投递")
    void disabledSceneIsSuppressed() {
        NotifyPipeline pipeline = pipeline(List.of(new SceneRoutingFilter()),
                SceneConfig.builder("SCENE_A").enabled(false).channels(List.of("recording")).build());

        pipeline.handle(NotifyEvent.builder("SCENE_A").build());

        assertThat(channel.receivers).isEmpty();
        assertThat(audit.records).anyMatch(record -> "SUPPRESSED".equals(record.status()));
        assertThat(audit.records.get(0).attempts()).isZero();
    }

    @Test
    @DisplayName("免打扰命中时事件交给 DeferredHandler 延后，不投递也不丢弃")
    void quietHoursDeferHandsEventToDeferredHandler() {
        CapturingDeferredHandler deferred = new CapturingDeferredHandler();
        NotifyPipeline pipeline = deferredPipeline(deferred);

        boolean accepted = pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());

        assertThat(accepted).isTrue();
        assertThat(channel.receivers).isEmpty();
        assertThat(deferred.events).hasSize(1);
        assertThat(deferred.untils.get(0)).isAfter(Instant.now());
        assertThat(audit.records).anyMatch(record ->
                "SUPPRESSED".equals(record.status()) && "deferred".equals(record.errorMsg()));
    }

    @Test
    @DisplayName("自定义 DeferredHandler 抛异常时退化为抑制，并保留审计")
    void customHandlerFailureFallsBackToAudit() {
        DeferredHandler broken = (event, until) -> {
            throw new IllegalStateException("boom");
        };
        NotifyPipeline pipeline = deferredPipeline(broken);

        boolean accepted = pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());

        assertThat(accepted).isFalse();
        assertThat(channel.receivers).isEmpty();
        assertThat(audit.records).anyMatch(record ->
                "SUPPRESSED".equals(record.status()) && "deferred-error".equals(record.errorMsg()));
    }

    /**
     * 构造带免打扰过滤器与指定延后处理器的管线。
     */
    private NotifyPipeline deferredPipeline(DeferredHandler deferredHandler) {
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(
                        Map.of("SCENE_A", SceneConfig.builder("SCENE_A").channels(List.of("recording")).build()),
                        SceneRegistry.emptyDefaults()))
                .filters(List.of(quietHoursCoveringNow(true)))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .auditRecorder(audit)
                .deferredHandler(deferredHandler)
                .build();
    }

    @Test
    @DisplayName("免打扰命中但没有 DeferredHandler 时退化为抑制：返回 false 并留下审计线索")
    void quietHoursDeferWithoutHandlerDegradesToSuppression() {
        NotifyPipeline pipeline = pipeline(List.of(quietHoursCoveringNow(true)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording")).build());

        boolean accepted = pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());

        assertThat(accepted).isFalse();
        assertThat(channel.receivers).isEmpty();
        assertThat(audit.records).anyMatch(record ->
                "SUPPRESSED".equals(record.status()) && "deferred-unsupported".equals(record.errorMsg()));
    }

    @Test
    @DisplayName("defer=false 时免打扰命中直接丢弃")
    void quietHoursDropWhenDeferDisabled() {
        NotifyPipeline pipeline = pipeline(List.of(quietHoursCoveringNow(false)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording")).build());

        boolean accepted = pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());

        assertThat(accepted).isFalse();
        assertThat(channel.receivers).isEmpty();
        assertThat(audit.records).anyMatch(record ->
                "SUPPRESSED".equals(record.status()) && "quiet-hours-drop".equals(record.errorMsg()));
    }

    @Test
    @DisplayName("T6：CRITICAL 直通免打扰，照常投递")
    void criticalBypassesQuietHours() {
        NotifyPipeline pipeline = pipeline(List.of(quietHoursCoveringNow(true)),
                SceneConfig.builder("SCENE_A").channels(List.of("recording")).build());

        pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.CRITICAL).build());

        assertThat(channel.receivers).hasSize(1);
    }

    /**
     * 构造一个「当前时刻必然落在窗口内」的免打扰过滤器：from=now-1h，to=now+1h（UTC）。
     */
    private static QuietHoursFilter quietHoursCoveringNow(boolean defer) {
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        return new QuietHoursFilter(now.minusHours(1), now.plusHours(1), ZoneId.of("UTC"), defer, true);
    }

    private static final class CapturingDeferredHandler implements DeferredHandler {

        private final List<NotifyEvent> events = new CopyOnWriteArrayList<>();
        private final List<Instant> untils = new CopyOnWriteArrayList<>();

        @Override
        public void defer(NotifyEvent event, Instant until) {
            events.add(event);
            untils.add(until);
        }
    }

    @Test
    @DisplayName("env/tenant 进入模板渲染参数")
    void shouldRenderEnvAndTenant() {
        SceneConfig scene = SceneConfig.builder("SCENE_A").channels(List.of("recording")).build();
        NotifyPipeline pipeline = NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of(scene.scene(), scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default",
                        new MessageTemplate("default", "[${env}] ${tenant}", "${tenant}")))
                .auditRecorder(audit)
                .env("staging")
                .tenant("acme")
                .build();

        pipeline.handle(NotifyEvent.builder("SCENE_A").build());

        assertThat(channel.contexts).hasSize(1);
        assertThat(channel.contexts.get(0).renderedTitle()).isEqualTo("[staging] acme");
        assertThat(channel.contexts.get(0).renderedContent()).isEqualTo("acme");
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

        private final String channelName;
        private final List<String> receivers = new CopyOnWriteArrayList<>();
        private final List<NotifyContext> contexts = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean failFirst = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();

        RecordingChannel() {
            this("recording");
        }

        RecordingChannel(String channelName) {
            this.channelName = channelName;
        }

        @Override
        public String name() {
            return channelName;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            attempts.incrementAndGet();
            if (failFirst.compareAndSet(true, false)) {
                throw new io.github.pkyit.notifyx.core.exception.ChannelTimeoutException("recording", null);
            }
            receivers.add(ctx.receiver());
            contexts.add(ctx);
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

    @Test
    @DisplayName("T24：3 个接收人 × 2 个渠道 → 6 条成功审计记录")
    void shouldFanOutAuditForEveryReceiverAndChannel() {
        RecordingChannel second = new RecordingChannel("recording2");
        NotifyPipeline pipeline = NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of("SCENE_A",
                        SceneConfig.builder("SCENE_A").channels(List.of("recording", "recording2")).build()),
                        SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel, second)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .auditRecorder(audit)
                .build();

        pipeline.handle(NotifyEvent.builder("SCENE_A")
                .receivers(java.util.List.of("a@x.com", "b@x.com", "c@x.com"))
                .build());

        assertThat(channel.receivers).hasSize(3);
        assertThat(second.receivers).hasSize(3);
        assertThat(audit.records.stream().filter(record -> "SUCCESS".equals(record.status())).count()).isEqualTo(6);
    }
}
