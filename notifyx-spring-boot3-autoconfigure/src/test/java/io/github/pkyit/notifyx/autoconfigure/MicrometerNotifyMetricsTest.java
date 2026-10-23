package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Micrometer 指标测试（设计书 §15.1）：指标名、标签基数、计数与耗时。
 *
 * @since 1.0
 */
class MicrometerNotifyMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerNotifyMetrics metrics = new MicrometerNotifyMetrics(registry);

    @Test
    @DisplayName("事件接收：按 scene/level/source 计数")
    void shouldCountReceivedEvents() {
        NotifyEvent event = NotifyEvent.builder("ORDER_CREATE_FAIL").level(Level.ERROR).build();

        metrics.eventReceived(event, "L2");
        metrics.eventReceived(event, "L2");

        assertThat(registry.get("notifyx.event.received")
                .tag("scene", "ORDER_CREATE_FAIL")
                .tag("level", "ERROR")
                .tag("source", "L2")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("投递结果：status 小写标签 + 非零耗时计入 Timer")
    void shouldRecordSendResult() {
        metrics.sendResult("email", "ORDER_CREATE_FAIL", SendResult.Status.SUCCESS, Duration.ofMillis(5));
        metrics.sendResult("email", "ORDER_CREATE_FAIL", null, Duration.ZERO);

        assertThat(registry.get("notifyx.send.total")
                .tag("channel", "email")
                .tag("status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notifyx.send.total")
                .tag("status", "unknown")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notifyx.send.duration")
                .tag("channel", "email")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("队列丢弃与过滤器拦截：低基数标签")
    void shouldRecordQueueAndFilterMetrics() {
        metrics.queueDiscarded("queue-full", Level.WARN);
        metrics.queueDiscarded("queue-full", null);
        metrics.filterRejected("dedupe", "ORDER_CREATE_FAIL");

        assertThat(registry.get("notifyx.queue.discarded")
                .tag("reason", "queue-full")
                .tag("level", "WARN")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notifyx.queue.discarded")
                .tag("level", "UNKNOWN")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notifyx.filter.rejected")
                .tag("filter", "dedupe")
                .tag("scene", "ORDER_CREATE_FAIL")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("自定义 prefix 生效，末尾点自动去掉")
    void shouldRespectCustomPrefix() {
        SimpleMeterRegistry custom = new SimpleMeterRegistry();
        MicrometerNotifyMetrics customMetrics = new MicrometerNotifyMetrics(custom, "acme.notifyx.");
        NotifyEvent event = NotifyEvent.builder("S1").level(Level.WARN).build();

        customMetrics.eventReceived(event, "L4");

        assertThat(custom.get("acme.notifyx.event.received").counter().count()).isEqualTo(1.0);
        assertThat(custom.getMeters())
                .allMatch(meter -> meter.getId().getName().startsWith("acme.notifyx."));
    }

    @Test
    @DisplayName("空 prefix 回退到 notifyx")
    void shouldFallbackToDefaultPrefix() {
        SimpleMeterRegistry custom = new SimpleMeterRegistry();
        MicrometerNotifyMetrics customMetrics = new MicrometerNotifyMetrics(custom, "  ");
        NotifyEvent event = NotifyEvent.builder("S1").build();

        customMetrics.eventReceived(event, "L4");

        assertThat(custom.get("notifyx.event.received").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("指标名统一 notifyx. 前缀且不使用 receiver/bizId 等高基数标签")
    void shouldKeepLowCardinality() {
        NotifyEvent event = NotifyEvent.builder("S1").bizId("BIZ-1").build();
        metrics.eventReceived(event, "L4");

        assertThat(registry.getMeters())
                .allMatch(meter -> meter.getId().getName().startsWith("notifyx."));
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .doesNotContain("receiver", "bizId", "eventId");
    }
}
