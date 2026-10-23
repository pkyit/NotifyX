package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.exception.TemplateRenderException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.RenderFormat;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.spi.RecordStatus;
import io.github.pkyit.notifyx.core.spi.TemplateEngine;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 渠道意外异常 / 空结果测试（架构审计 A05）：必须进入重试、审计与失败落盘，而不是
 * 绕过熔断直接落到管线外层 catch。
 *
 * @since 1.0
 */
class NotifyPipelineFailureContainmentTest {

    private final ScriptedChannel channel = new ScriptedChannel();
    private final RecordingAudit audit = new RecordingAudit();
    private final RecordingFailureStore failures = new RecordingFailureStore();

    @Test
    @DisplayName("渠道抛非 ChannelException 时按可重试处理，恢复后成功")
    void shouldRetryUnexpectedRuntimeException() {
        channel.errors.add(new IllegalStateException("boom"));
        channel.errors.add(new IllegalStateException("boom"));

        boolean handled = pipeline(new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .handle(event(new AtomicInteger()));

        assertThat(handled).isTrue();
        assertThat(channel.calls).isEqualTo(3);
        assertThat(channel.delivered).hasSize(1);
        assertThat(audit.statuses()).containsExactly("SUCCESS");
        assertThat(failures.records).isEmpty();
    }

    @Test
    @DisplayName("渠道持续抛非 ChannelException 时落盘、审计并回调 onError")
    void shouldPersistUnexpectedRuntimeException() {
        channel.errors.add(new IllegalStateException("boom"));
        channel.errors.add(new IllegalStateException("boom"));
        AtomicInteger onError = new AtomicInteger();

        boolean handled = pipeline(new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .handle(event(onError));

        assertThat(handled).isFalse();
        assertThat(channel.calls).isEqualTo(2);
        assertThat(audit.statuses()).containsExactly("FAIL_RETRYABLE");
        assertThat(failures.records).hasSize(1);
        assertThat(onError).hasValue(1);
    }

    @Test
    @DisplayName("渠道返回 null 不能被当成成功")
    void shouldTreatNullResultAsFailure() {
        channel.nullResult = true;
        AtomicInteger onError = new AtomicInteger();

        boolean handled = pipeline(new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .handle(event(onError));

        assertThat(handled).isFalse();
        assertThat(channel.calls).isEqualTo(1);
        assertThat(audit.statuses()).containsExactly("FAIL_RETRYABLE");
        assertThat(failures.records).hasSize(1);
        assertThat(onError).hasValue(1);
    }

    @Test
    @DisplayName("模板渲染异常进入审计与失败落盘，不调用渠道")
    void shouldPersistTemplateRenderFailure() {
        AtomicInteger onError = new AtomicInteger();

        boolean handled = pipeline(new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(5), 0.0),
                new FailingTemplateEngine()).handle(event(onError));

        assertThat(handled).isFalse();
        assertThat(channel.calls).isZero();
        assertThat(audit.statuses()).containsExactly("FAIL_NON_RETRYABLE");
        assertThat(audit.records.get(0).attempts()).isZero();
        assertThat(failures.records).hasSize(1);
        assertThat(failures.records.get(0).attempts()).isZero();
        assertThat(onError).hasValue(1);
    }

    private NotifyPipeline pipeline(RetryPolicy retryPolicy) {
        return pipeline(retryPolicy, new SimpleTemplateEngine());
    }

    private NotifyPipeline pipeline(RetryPolicy retryPolicy, TemplateEngine templateEngine) {
        SceneConfig scene = SceneConfig.builder("SCENE_A").channels(List.of("scripted")).build();
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of(scene.scene(), scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(templateEngine)
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .retryPolicy(retryPolicy)
                .failureStore(failures)
                .auditRecorder(audit)
                .build();
    }

    private static NotifyEvent event(AtomicInteger onError) {
        return NotifyEvent.builder("SCENE_A")
                .level(Level.ERROR)
                .bizId("B1")
                .onError(error -> onError.incrementAndGet())
                .build();
    }

    private static final class ScriptedChannel implements NotifyChannel {

        private final Deque<RuntimeException> errors = new ArrayDeque<>();
        private final List<NotifyContext> delivered = new CopyOnWriteArrayList<>();
        private boolean nullResult;
        private int calls;

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public int maxRetries() {
            return 0;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            calls++;
            if (!errors.isEmpty()) {
                throw errors.removeFirst();
            }
            if (nullResult) {
                return null;
            }
            delivered.add(ctx);
            return SendResult.success("1", null, Duration.ZERO);
        }
    }

    private static final class FailingTemplateEngine implements TemplateEngine {

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public String render(String template, Map<String, Object> params, RenderFormat format) {
            throw new TemplateRenderException("template boom");
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }

        private List<String> statuses() {
            return records.stream().map(AuditRecord::status).toList();
        }
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
