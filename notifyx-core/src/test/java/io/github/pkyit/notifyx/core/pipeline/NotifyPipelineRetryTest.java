package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.exception.ChannelAuthException;
import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.exception.ChannelTimeoutException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 管线重试与熔断分支测试（设计书 §9.7 / §9.8）：可重试/不可重试、场景重试覆盖、熔断半开。
 *
 * @since 1.0
 */
class NotifyPipelineRetryTest {

    private final ScriptedChannel channel = new ScriptedChannel();
    private final RecordingAudit audit = new RecordingAudit();

    @Test
    @DisplayName("可重试异常按 maxAttempts 重试，最终成功")
    void shouldRetryUntilSuccess() {
        channel.failures.add(new ChannelTimeoutException("scripted", null));
        channel.failures.add(new ChannelTimeoutException("scripted", null));
        NotifyPipeline pipeline = pipeline(SceneConfig.builder("SCENE_A").channels(List.of("scripted")).build(),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.0), 10, Duration.ofMillis(100));

        pipeline.handle(event());

        assertThat(channel.calls).isEqualTo(3);
        assertThat(channel.delivered).hasSize(1);
        assertThat(audit.statuses()).containsExactly("SUCCESS");
    }

    @Test
    @DisplayName("不可重试异常只发一次")
    void shouldNotRetryAuthFailure() {
        channel.failures.add(new ChannelAuthException("scripted", "bad credentials"));
        NotifyPipeline pipeline = pipeline(SceneConfig.builder("SCENE_A").channels(List.of("scripted")).build(),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.0), 10, Duration.ofMillis(100));

        pipeline.handle(event());

        assertThat(channel.calls).isEqualTo(1);
        assertThat(channel.delivered).isEmpty();
        assertThat(audit.statuses()).containsExactly("FAIL_NON_RETRYABLE");
    }

    @Test
    @DisplayName("场景 retry 覆盖：retry=1 时不再重试")
    void sceneRetryShouldCapAttempts() {
        channel.failures.add(new ChannelTimeoutException("scripted", null));
        NotifyPipeline pipeline = pipeline(SceneConfig.builder("SCENE_A").channels(List.of("scripted")).retry(1).build(),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.0), 10, Duration.ofMillis(100));

        pipeline.handle(event());

        assertThat(channel.calls).isEqualTo(1);
        assertThat(audit.statuses()).containsExactly("FAIL_RETRYABLE");
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断，CIRCUIT_OPEN 不再调用渠道；到期后半开成功关闭")
    void shouldOpenCircuitThenHalfOpen() throws Exception {
        channel.failures.add(new ChannelTimeoutException("scripted", null));
        channel.failures.add(new ChannelTimeoutException("scripted", null));
        NotifyPipeline pipeline = pipeline(SceneConfig.builder("SCENE_A").channels(List.of("scripted")).build(),
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(5), 0.0), 2, Duration.ofMillis(100));

        pipeline.handle(event());
        pipeline.handle(event());
        pipeline.handle(event());

        assertThat(channel.calls).isEqualTo(2);
        assertThat(audit.statuses()).containsExactly("FAIL_RETRYABLE", "FAIL_RETRYABLE", "CIRCUIT_OPEN");

        Thread.sleep(150);
        pipeline.handle(event());

        assertThat(channel.calls).isEqualTo(3);
        assertThat(channel.delivered).hasSize(1);
        assertThat(audit.statuses()).last().isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("场景没有可用渠道时安静跳过，不抛异常也不审计")
    void shouldSkipWhenNoChannel() {
        NotifyPipeline pipeline = pipeline(SceneConfig.builder("SCENE_A").build(),
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(5), 0.0), 2, Duration.ofMillis(100));

        pipeline.handle(event());

        assertThat(channel.calls).isZero();
        assertThat(audit.records).isEmpty();
    }

    private NotifyPipeline pipeline(SceneConfig scene, RetryPolicy retryPolicy, int failureThreshold,
                                    Duration openDuration) {
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of(scene.scene(), scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .retryPolicy(retryPolicy)
                .circuitConfig(new NotifyPipeline.CircuitConfig(failureThreshold, openDuration, Duration.ofSeconds(1)))
                .auditRecorder(audit)
                .build();
    }

    private static NotifyEvent event() {
        return NotifyEvent.builder("SCENE_A").level(Level.ERROR).bizId("B1").build();
    }

    private static final class ScriptedChannel implements NotifyChannel {

        private final List<ChannelException> failures = new ArrayList<>();
        private final List<NotifyContext> delivered = new CopyOnWriteArrayList<>();
        private int calls;

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public int maxRetries() {
            // 0 = 使用 RetryPolicy 的 maxAttempts，避免渠道默认值（3）干扰用例
            return 0;
        }

        @Override
        public SendResult send(NotifyContext ctx) throws ChannelException {
            calls++;
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            delivered.add(ctx);
            return SendResult.success("1", null, Duration.ZERO);
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
