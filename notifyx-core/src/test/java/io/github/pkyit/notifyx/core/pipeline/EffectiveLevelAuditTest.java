package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 生效级别测试（设计书 §6.8 / §9.4）：审计记录的是「事件与场景取高」后的级别，
 * 而不是事件原始级别。
 *
 * @since 1.0
 */
class EffectiveLevelAuditTest {

    private final RecordingAudit audit = new RecordingAudit();

    @Test
    @DisplayName("事件 WARN + 场景 ERROR：审计记为 ERROR")
    void shouldAuditSceneLevelWhenHigher() {
        NotifyPipeline pipeline = pipeline(Level.ERROR);

        pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.WARN).build());

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.get(0).level()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("事件 CRITICAL + 场景 INFO：审计记为 CRITICAL")
    void shouldAuditEventLevelWhenHigher() {
        NotifyPipeline pipeline = pipeline(Level.INFO);

        pipeline.handle(NotifyEvent.builder("SCENE_A").level(Level.CRITICAL).build());

        assertThat(audit.records.get(0).level()).isEqualTo("CRITICAL");
    }

    private NotifyPipeline pipeline(Level sceneLevel) {
        SceneConfig scene = SceneConfig.builder("SCENE_A")
                .level(sceneLevel)
                .channels(List.of("ok"))
                .build();
        NotifyChannel channel = new NotifyChannel() {
            @Override
            public String name() {
                return "ok";
            }

            @Override
            public SendResult send(NotifyContext ctx) {
                return SendResult.success("1", null, Duration.ZERO);
            }
        };
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of("SCENE_A", scene), SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .auditRecorder(audit)
                .retryPolicy(new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .build();
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }
}
