package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.pipeline.filter.DedupeFilter;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 聚合与管线集成测试（设计书 §9.5）：被去重的事件进入聚合，窗口结束补发一条摘要。
 *
 * @since 1.0
 */
class AggregationPipelineTest {

    private final List<NotifyEvent> delivered = new CopyOnWriteArrayList<>();
    private final MemoryDedupeStore dedupeStore = new MemoryDedupeStore();

    @Test
    @DisplayName("同指纹重复事件：首条直达，其余聚合为摘要")
    void shouldAggregateDuplicates() throws Exception {
        Aggregator aggregator = new Aggregator(new Aggregator.Config(true, Duration.ofMillis(100), 100));
        try {
            SceneConfig scene = SceneConfig.builder("ORDER_CREATE_FAIL")
                    .channels(List.of("capture"))
                    .silentWindow(Duration.ofMinutes(5))
                    .build();
            NotifyPipeline pipeline = NotifyPipeline.builder()
                    .sceneRegistry(SceneRegistry.of(Map.of("ORDER_CREATE_FAIL", scene), SceneRegistry.emptyDefaults()))
                    .filters(List.of(new DedupeFilter(dedupeStore)))
                    .channelRegistry(ChannelRegistry.of(List.of(new CapturingChannel(delivered))))
                    .templateEngine(new SimpleTemplateEngine())
                    .templates(Map.of("default", new MessageTemplate("default", "[${level}] ${scene}", "${content}")))
                    .dedupe(dedupeStore)
                    .aggregator(aggregator)
                    .build();
            aggregator.sink(pipeline::handle);

            pipeline.handle(event("SO1"));
            pipeline.handle(event("SO1"));
            pipeline.handle(event("SO1"));

            NotifyEvent summary = awaitSummary();
            assertThat(delivered).hasSize(2);
            assertThat(summary.tag("aggregate")).isEqualTo("true");
            assertThat(summary.params()).containsEntry("aggregateCount", 2L);
            assertThat(summary.title()).contains("聚合告警");
        } finally {
            aggregator.close();
        }
    }

    private NotifyEvent awaitSummary() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (NotifyEvent event : delivered) {
                if ("true".equals(event.tag("aggregate"))) {
                    return event;
                }
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("no aggregated summary delivered, got " + delivered);
    }

    private static NotifyEvent event(String bizId) {
        return NotifyEvent.builder("ORDER_CREATE_FAIL").bizId(bizId).level(Level.ERROR).build();
    }

    private static final class CapturingChannel implements NotifyChannel {

        private final List<NotifyEvent> delivered;

        private CapturingChannel(List<NotifyEvent> delivered) {
            this.delivered = delivered;
        }

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            delivered.add(ctx.event());
            return SendResult.success("1", null, Duration.ZERO);
        }
    }
}
