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
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.DefaultMasker;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 脱敏总开关测试：{@code mask.enabled=false} 时审计 receiver 不再脱敏，
 * {@code true} 时按 {@code mask.before-audit} 生效。
 *
 * @since 1.0
 */
class MaskEnabledTest {

    private final RecordingChannel channel = new RecordingChannel();
    private final RecordingAudit audit = new RecordingAudit();

    @Test
    @DisplayName("mask.enabled=false 时审计保留完整 receiver")
    void shouldKeepReceiverWhenMaskingDisabled() {
        pipeline(false).handle(event());

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).receiver()).isEqualTo("13812345678");
    }

    @Test
    @DisplayName("mask.enabled=true 时审计 receiver 脱敏")
    void shouldMaskReceiverWhenMaskingEnabled() {
        pipeline(true).handle(event());

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).receiver()).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("mask.enabled=true 时按接收人类型脱敏（邮箱）")
    void shouldMaskEmailReceiver() {
        pipeline(true).handle(NotifyEvent.builder("SCENE_A")
                .level(Level.ERROR)
                .receiver("alice@example.com")
                .build());

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).receiver()).isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("mask.enabled=true 时审计 bizId 中的手机号脱敏")
    void shouldMaskBizId() {
        pipeline(true).handle(NotifyEvent.builder("SCENE_A")
                .level(Level.ERROR)
                .bizId("13812345678")
                .build());

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).bizId()).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("mask.enabled=true 时审计 failureReason 中的邮箱脱敏")
    void shouldMaskFailureReason() {
        channel.fail = true;

        pipeline(true).handle(event());

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).errorMsg()).contains("a***@example.com");
        assertThat(audit.records.get(0).errorMsg()).doesNotContain("alice@example.com");
    }

    @Test
    @DisplayName("mask.before-send=true 时渲染内容与业务参数都脱敏，原事件不变")
    void shouldMaskBeforeSend() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .param("phone", "13812345678")
                .param("note", "alice@example.com")
                .build();

        pipeline(true, true).handle(event);

        NotifyContext ctx = channel.contexts.get(0);
        assertThat(ctx.renderedTitle()).isEqualTo("phone=138****5678");
        assertThat(ctx.renderedContent()).isEqualTo("email=a***@example.com");
        assertThat(ctx.params().get("phone")).isEqualTo("138****5678");
        assertThat(event.params().get("phone")).isEqualTo("13812345678");
    }

    @Test
    @DisplayName("mask.before-send=false（默认）时渠道看到原文")
    void shouldNotMaskBeforeSendByDefault() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .param("phone", "13812345678")
                .param("note", "alice@example.com")
                .build();

        pipeline(true, false).handle(event);

        NotifyContext ctx = channel.contexts.get(0);
        assertThat(ctx.renderedTitle()).isEqualTo("phone=13812345678");
        assertThat(ctx.renderedContent()).isEqualTo("email=alice@example.com");
        assertThat(ctx.params().get("phone")).isEqualTo("13812345678");
    }

    private NotifyPipeline pipeline(boolean maskEnabled) {
        return pipeline(maskEnabled, false);
    }

    private NotifyPipeline pipeline(boolean maskEnabled, boolean maskBeforeSend) {
        SceneConfig scene = SceneConfig.builder("SCENE_A").channels(List.of("recording")).build();
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of(scene.scene(), scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "phone=${phone}", "email=${note}")))
                .auditRecorder(audit)
                .masker(new DefaultMasker())
                .maskBeforeAudit(true)
                .maskEnabled(maskEnabled)
                .maskBeforeSend(maskBeforeSend)
                .retryPolicy(new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1), 0.0))
                .build();
    }

    private static NotifyEvent event() {
        return NotifyEvent.builder("SCENE_A")
                .level(Level.ERROR)
                .receiver("13812345678")
                .build();
    }

    private static final class RecordingChannel implements NotifyChannel {

        private final List<NotifyContext> contexts = new CopyOnWriteArrayList<>();
        private volatile boolean fail;

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            if (fail) {
                throw new ChannelTimeoutException("recording", new IllegalStateException("failed for alice@example.com"));
            }
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
}
