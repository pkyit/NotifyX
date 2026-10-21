package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 实例内聚合测试（设计书 §9.5 T0）：窗口收拢、级别取高、样本、关闭汇总、关闭开关。
 *
 * @since 1.0
 */
class AggregatorTest {

    private final List<NotifyEvent> emitted = new CopyOnWriteArrayList<>();

    @Test
    @DisplayName("窗口结束时把同指纹事件收拢成一条摘要")
    void shouldAggregateWithinWindow() throws Exception {
        Aggregator aggregator = new Aggregator(new Aggregator.Config(true, Duration.ofMillis(80), 100));
        aggregator.sink(emitted::add);
        try {
            aggregator.record(event("SO1"), Level.ERROR, "key-1", "inst-1");
            aggregator.record(event("SO2"), Level.WARN, "key-1", "inst-1");
            aggregator.record(event("SO3"), Level.CRITICAL, "key-1", "inst-2");

            NotifyEvent summary = awaitOne(2000);
            assertThat(summary.scene()).isEqualTo("ORDER_CREATE_FAIL");
            assertThat(summary.level()).isEqualTo(Level.CRITICAL);
            assertThat(summary.dedupeKey()).startsWith("agg:key-1:");
            assertThat(summary.tag("aggregate")).isEqualTo("true");
            assertThat(summary.params()).containsEntry("aggregateCount", 3L);
            assertThat(summary.params()).containsEntry("aggregateInstances", 2);
            assertThat(summary.params().get("aggregateFirstSample").toString()).contains("SO1");
            assertThat(summary.params().get("aggregateLastSample").toString()).contains("SO3");
            assertThat(summary.title()).contains("聚合告警", "3 条");
        } finally {
            aggregator.close();
        }
    }

    @Test
    @DisplayName("关闭时立即汇总未结束的桶")
    void shouldFlushOnClose() {
        Aggregator aggregator = new Aggregator(new Aggregator.Config(true, Duration.ofSeconds(30), 100));
        aggregator.sink(emitted::add);
        aggregator.record(event("SO1"), Level.ERROR, "key-2", "inst-1");

        aggregator.close();

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).params()).containsEntry("aggregateCount", 1L);
    }

    @Test
    @DisplayName("不同指纹进入不同桶")
    void shouldKeepSeparateBuckets() {
        Aggregator aggregator = new Aggregator(new Aggregator.Config(true, Duration.ofSeconds(30), 100));
        aggregator.sink(emitted::add);
        aggregator.record(event("SO1"), Level.ERROR, "key-a", "inst-1");
        aggregator.record(event("SO2"), Level.ERROR, "key-b", "inst-1");

        aggregator.flushAll();
        aggregator.close();

        assertThat(emitted).hasSize(2);
    }

    @Test
    @DisplayName("未启用时不产生摘要")
    void shouldDoNothingWhenDisabled() throws Exception {
        Aggregator aggregator = new Aggregator(Aggregator.Config.disabled());
        aggregator.sink(emitted::add);
        try {
            aggregator.record(event("SO1"), Level.ERROR, "key-3", "inst-1");
            Thread.sleep(150);
            assertThat(emitted).isEmpty();
        } finally {
            aggregator.close();
        }
    }

    private NotifyEvent awaitOne(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (emitted.isEmpty() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(emitted).as("aggregated summary").isNotEmpty();
        return emitted.get(0);
    }

    private static NotifyEvent event(String bizId) {
        return NotifyEvent.builder("ORDER_CREATE_FAIL").bizId(bizId).param("reason", "库存不足").build();
    }
}
