package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.InMemoryFailureStore;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 渠道返回 {@link SendResult} 失败状态（而不是抛异常）时的管线语义测试。
 *
 * <p>渠道 SPI 允许用返回值表达失败；管线必须按状态重试、记熔断、审计与落盘，
 * 不能把所有非 null 结果都当成成功。</p>
 *
 * @since 1.0
 */
class NotifyPipelineResultStatusTest {

    private final ScriptedResultChannel channel = new ScriptedResultChannel();
    private final RecordingAudit audit = new RecordingAudit();
    private final InMemoryFailureStore failures = new InMemoryFailureStore();

    @Test
    @DisplayName("FAIL_RETRYABLE 结果按 maxAttempts 重试，最终成功")
    void shouldRetryRetryableResult() {
        channel.results.add(SendResult.retryable("temporary", Duration.ZERO));
        channel.results.add(SendResult.success("1", null, Duration.ZERO));

        boolean handled = pipeline(5).handle(event());

        assertThat(handled).isTrue();
        assertThat(channel.calls).isEqualTo(2);
        assertThat(audit.statuses()).containsExactly("SUCCESS");
        assertThat(failures.pendingCount()).isZero();
    }

    @Test
    @DisplayName("FAIL_NON_RETRYABLE 结果只发一次，进入审计与失败落盘")
    void shouldPersistNonRetryableResult() {
        channel.results.add(SendResult.nonRetryable("bad request", Duration.ZERO));

        boolean handled = pipeline(5).handle(event());

        assertThat(handled).isFalse();
        assertThat(channel.calls).isEqualTo(1);
        assertThat(audit.statuses()).containsExactly("FAIL_NON_RETRYABLE");
        assertThat(failures.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复 FAIL_RETRYABLE 结果会打开熔断，后续事件得到 CIRCUIT_OPEN")
    void shouldOpenCircuitOnRetryableResults() {
        NotifyEvent event = event();
        channel.results.add(SendResult.retryable("temporary", Duration.ZERO));
        channel.results.add(SendResult.retryable("temporary", Duration.ZERO));
        NotifyPipeline pipeline = pipeline(2, 2);

        assertThat(pipeline.handle(event)).isFalse();
        assertThat(channel.calls).isEqualTo(2);
        assertThat(audit.statuses()).containsExactly("FAIL_RETRYABLE");

        assertThat(pipeline.handle(event)).isFalse();
        assertThat(channel.calls).isEqualTo(2);
        assertThat(audit.statuses()).containsExactly("FAIL_RETRYABLE", "CIRCUIT_OPEN");
    }

    @Test
    @DisplayName("渠道主动抑制结果不算失败，也不打开熔断")
    void shouldNotFailOnSuppressedResult() {
        channel.results.add(SendResult.suppressed(SendResult.Status.SUPPRESSED, "channel skip"));

        boolean handled = pipeline(1).handle(event());

        assertThat(handled).isTrue();
        assertThat(channel.calls).isEqualTo(1);
        assertThat(audit.statuses()).containsExactly("SUPPRESSED");
        assertThat(failures.pendingCount()).isZero();
    }

    private NotifyPipeline pipeline(int failureThreshold) {
        return pipeline(failureThreshold, 3);
    }

    private NotifyPipeline pipeline(int failureThreshold, int maxAttempts) {
        SceneConfig scene = SceneConfig.builder("SCENE_A").channels(List.of("scripted")).build();
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of(scene.scene(), scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .retryPolicy(new RetryPolicy(maxAttempts, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .circuitConfig(new NotifyPipeline.CircuitConfig(failureThreshold, Duration.ofSeconds(30),
                        Duration.ofMinutes(1)))
                .failureStore(failures)
                .auditRecorder(audit)
                .build();
    }

    private static NotifyEvent event() {
        return NotifyEvent.builder("SCENE_A").level(Level.ERROR).bizId("B1").build();
    }

    private static final class ScriptedResultChannel implements NotifyChannel {

        private final Deque<SendResult> results = new ArrayDeque<>();
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
            return results.isEmpty() ? SendResult.success("1", null, Duration.ZERO) : results.removeFirst();
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
}
