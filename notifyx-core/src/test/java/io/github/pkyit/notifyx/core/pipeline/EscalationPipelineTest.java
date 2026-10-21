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
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 升级策略与管线集成测试（设计书 §9.6）：达到阈值后改走升级渠道，恢复场景后回落。
 *
 * @since 1.0
 */
class EscalationPipelineTest {

    private final List<String> routedChannels = new CopyOnWriteArrayList<>();

    @Test
    @DisplayName("连续 2 次后改走 upgrade 渠道")
    void shouldRouteToUpgradeChannels() {
        NotifyPipeline pipeline = pipeline(new EscalationPolicy(List.of(
                new EscalationPolicy.Rule("ORDER_CREATE_FAIL", 2, Duration.ofMinutes(10),
                        List.of("upgrade"), false)), Map.of()));

        pipeline.handle(event("SO1"));
        pipeline.handle(event("SO2"));
        pipeline.handle(event("SO3"));

        assertThat(routedChannels).containsExactly("primary", "upgrade", "upgrade");
    }

    @Test
    @DisplayName("恢复场景清零后回到 primary")
    void shouldFallBackAfterRecoveryScene() {
        EscalationPolicy policy = new EscalationPolicy(
                List.of(new EscalationPolicy.Rule("ORDER_CREATE_FAIL", 2, Duration.ofMinutes(10),
                        List.of("upgrade"), false)),
                Map.of("ORDER_CREATE_RECOVERED", List.of("ORDER_CREATE_FAIL")));
        NotifyPipeline pipeline = pipeline(policy);

        pipeline.handle(event("SO1"));
        pipeline.handle(event("SO2"));
        pipeline.handle(NotifyEvent.builder("ORDER_CREATE_RECOVERED").level(Level.INFO).build());
        pipeline.handle(event("SO3"));

        // 恢复场景本身也会按自身配置投递一次 primary
        assertThat(routedChannels).containsExactly("primary", "upgrade", "primary", "primary");
    }

    private NotifyPipeline pipeline(EscalationPolicy policy) {
        SceneConfig scene = SceneConfig.builder("ORDER_CREATE_FAIL")
                .channels(List.of("primary"))
                .build();
        SceneConfig recovery = SceneConfig.builder("ORDER_CREATE_RECOVERED")
                .channels(List.of("primary"))
                .build();
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(
                        Map.of("ORDER_CREATE_FAIL", scene, "ORDER_CREATE_RECOVERED", recovery),
                        SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(
                        new RecordingChannel("primary"), new RecordingChannel("upgrade"))))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "c")))
                .escalation(policy)
                .build();
    }

    private static NotifyEvent event(String bizId) {
        return NotifyEvent.builder("ORDER_CREATE_FAIL").bizId(bizId).level(Level.ERROR).build();
    }

    private final class RecordingChannel implements NotifyChannel {

        private final String name;

        private RecordingChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            routedChannels.add(name);
            return SendResult.success("1", null, Duration.ZERO);
        }
    }
}
