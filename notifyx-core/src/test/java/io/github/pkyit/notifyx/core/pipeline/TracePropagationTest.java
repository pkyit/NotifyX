package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.MdcTraceContext;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * Trace 贯通测试（设计书 15.3）：MDC → 渲染内容 + 渠道上下文 + 审计记录。
 *
 * @since 1.0
 */
class TracePropagationTest {

    private final CapturingChannel channel = new CapturingChannel();
    private final CapturingAudit audit = new CapturingAudit();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 里的 traceId/spanId 进入渲染内容、NotifyContext 与审计")
    void shouldPropagateTraceFromMdc() {
        MDC.put("traceId", "trace-abc");
        MDC.put("spanId", "span-123");
        NotifyPipeline pipeline = pipeline();

        pipeline.handle(NotifyEvent.builder("SCENE_A").bizId("B1").build());

        assertThat(channel.contexts).hasSize(1);
        NotifyContext context = channel.contexts.get(0);
        assertThat(context.traceId()).isEqualTo("trace-abc");
        assertThat(context.spanId()).isEqualTo("span-123");
        assertThat(context.renderedContent()).contains("trace=trace-abc");
        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).traceId()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("事件自带 trace 标签优先于当前线程 MDC")
    void eventTagWinsOverMdc() {
        MDC.put("traceId", "mdc-trace");
        NotifyPipeline pipeline = pipeline();

        pipeline.handle(NotifyEvent.builder("SCENE_A").tag("traceId", "event-trace").build());

        assertThat(channel.contexts.get(0).traceId()).isEqualTo("event-trace");
        assertThat(channel.contexts.get(0).renderedContent()).contains("trace=event-trace");
    }

    @Test
    @DisplayName("没有 trace 时渲染为空字符串而不是 null")
    void shouldRenderEmptyWhenNoTrace() {
        NotifyPipeline pipeline = pipeline();

        pipeline.handle(NotifyEvent.builder("SCENE_A").build());

        assertThat(channel.contexts.get(0).traceId()).isNull();
        assertThat(channel.contexts.get(0).renderedContent()).contains("trace=").doesNotContain("null");
    }

    private NotifyPipeline pipeline() {
        SceneConfig scene = SceneConfig.builder("SCENE_A")
                .channels(List.of("capture"))
                .silentWindow(Duration.ofMinutes(1))
                .build();
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of("SCENE_A", scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content} trace=${traceId}")))
                .dedupe(new MemoryDedupeStore())
                .auditRecorder(audit)
                .traceContext(new MdcTraceContext())
                .build();
    }

    private static final class CapturingChannel implements NotifyChannel {

        private final List<NotifyContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            contexts.add(ctx);
            return SendResult.success("1", null, Duration.ZERO);
        }
    }

    private static final class CapturingAudit implements AuditRecorder {

        private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }
}
