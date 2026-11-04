package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.exception.ChannelProtocolException;
import io.github.pkyit.notifyx.core.exception.ChannelTimeoutException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 熔断器半开恢复回归测试。
 *
 * <p>背景：半开探测请求得到「既非成功也非失败」的结果（抑制状态、不可重试结果、不可重试异常）时，
 * 必须中性释放探测标记，否则熔断器永久停留在 HALF_OPEN，渠道直到进程重启才恢复。
 * 本测试覆盖三条曾导致卡死的路径。</p>
 *
 * @since 1.0.4
 */
class CircuitBreakerHalfOpenRegressionTest {

    @Test
    @DisplayName("半开探测命中不可重试结果后不卡死，后续成功请求可关闭熔断")
    void shouldNotStickWhenProbeGetsNonRetryableResult() throws Exception {
        ScriptedChannel channel = new ScriptedChannel();
        NotifyPipeline pipeline = pipeline(channel, 1);

        // 第一次可重试失败 → 熔断打开（阈值 1）
        channel.enqueue(new ChannelTimeoutException("scripted", null));
        assertThat(pipeline.handle(event("B1"))).isFalse();

        Thread.sleep(80);

        // 半开探测恰好命中不可重试结果（如收件人地址错误）
        channel.enqueue(SendResult.nonRetryable("550 bad address", Duration.ZERO));
        assertThat(pipeline.handle(event("B2"))).isFalse();

        // 回归断言：探测标记已释放，下一条成功请求能到达渠道并关闭熔断
        channel.enqueue(SendResult.success("m3", null, Duration.ZERO));
        assertThat(pipeline.handle(event("B3"))).as("breaker must not stick in HALF_OPEN").isTrue();
        assertThat(channel.invocations()).isEqualTo(3);
    }

    @Test
    @DisplayName("半开探测命中不可重试异常后不卡死，后续成功请求可关闭熔断")
    void shouldNotStickWhenProbeGetsNonRetryableException() throws Exception {
        ScriptedChannel channel = new ScriptedChannel();
        NotifyPipeline pipeline = pipeline(channel, 1);

        channel.enqueue(new ChannelTimeoutException("scripted", null));
        assertThat(pipeline.handle(event("B1"))).isFalse();

        Thread.sleep(80);

        // 半开探测抛出协议类异常（不可重试）
        channel.enqueue(new ChannelProtocolException("scripted", "550 mailbox unavailable"));
        assertThat(pipeline.handle(event("B2"))).isFalse();

        channel.enqueue(SendResult.success("m3", null, Duration.ZERO));
        assertThat(pipeline.handle(event("B3"))).as("breaker must not stick in HALF_OPEN").isTrue();
        assertThat(channel.invocations()).isEqualTo(3);
    }

    @Test
    @DisplayName("关闭状态下不可重试结果不计熔断失败，与结果分支口径统一")
    void nonRetryableOutcomeMustNotCountTowardFailures() {
        ScriptedChannel channel = new ScriptedChannel();
        NotifyPipeline pipeline = pipeline(channel, 2);

        // 不可重试异常不计失败（修复前会计入）
        channel.enqueue(new ChannelProtocolException("scripted", "550 bad address"));
        assertThat(pipeline.handle(event("B1"))).isFalse();

        // 一次可重试失败 → 计数 1，仍未达到阈值 2
        channel.enqueue(new ChannelTimeoutException("scripted", null));
        assertThat(pipeline.handle(event("B2"))).isFalse();

        // 回归断言：熔断未打开，第三条请求能到达渠道
        channel.enqueue(SendResult.success("m3", null, Duration.ZERO));
        assertThat(pipeline.handle(event("B3"))).isTrue();
        assertThat(channel.invocations()).isEqualTo(3);
    }

    private static NotifyEvent event(String bizId) {
        return NotifyEvent.builder("SCENE_A").level(Level.ERROR).bizId(bizId).build();
    }

    private static NotifyPipeline pipeline(ScriptedChannel channel, int failureThreshold) {
        return NotifyPipeline.builder()
                .sceneRegistry(SceneRegistry.of(Map.of("SCENE_A",
                        SceneConfig.builder("SCENE_A").channels(List.of("scripted")).build()),
                        SceneRegistry.emptyDefaults()))
                .channelRegistry(ChannelRegistry.of(List.of(channel)))
                .templateEngine(new SimpleTemplateEngine())
                .templates(Map.of("default", new MessageTemplate("default", "t", "${content}")))
                .retryPolicy(new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(5), 0.0))
                .circuitConfig(new NotifyPipeline.CircuitConfig(
                        failureThreshold, Duration.ofMillis(30), Duration.ofMillis(200)))
                .build();
    }

    /** 按脚本顺序返回结果或抛出异常的测试渠道。 */
    private static final class ScriptedChannel implements NotifyChannel {

        private final Queue<Object> script = new ConcurrentLinkedQueue<>();
        private final AtomicInteger invocationCount = new AtomicInteger();

        void enqueue(Object behavior) {
            script.add(behavior);
        }

        int invocations() {
            return invocationCount.get();
        }

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
            invocationCount.incrementAndGet();
            Object behavior = script.poll();
            if (behavior instanceof SendResult result) {
                return result;
            }
            if (behavior instanceof RuntimeException ex) {
                throw ex;
            }
            return SendResult.success("m-default", null, Duration.ZERO);
        }
    }
}
