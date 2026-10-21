package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.spring.executor.PriorityNotifyExecutor;

/**
 * 优雅关闭测试（设计书 9.10）：队列排空、渠道释放、停止幂等。
 *
 * @since 1.0
 */
class NotifyxLifecycleTest {

    private final RecordingChannel channel = new RecordingChannel();

    @Test
    @DisplayName("stop() 排空队列后才关闭渠道")
    void shouldDrainThenCloseChannels() {
        PriorityNotifyExecutor executor = executor();
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            executor.execute(io.github.pkyit.notifyx.core.model.Level.ERROR, "k" + i, () -> {
                sleep(40);
                executed.incrementAndGet();
            });
        }
        NotifyxLifecycle lifecycle = new NotifyxLifecycle(executor, List.of(channel), Duration.ofSeconds(5));
        lifecycle.start();

        lifecycle.stop();

        assertThat(executed).hasValue(3);
        assertThat(channel.closed).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop 幂等：重复调用不重复关闭渠道、不抛异常")
    void shouldBeIdempotent() {
        PriorityNotifyExecutor executor = executor();
        NotifyxLifecycle lifecycle = new NotifyxLifecycle(executor, List.of(channel), Duration.ofSeconds(1));
        lifecycle.start();

        lifecycle.stop();
        lifecycle.stop();

        assertThat(channel.closeCount).hasValue(1);
    }

    @Test
    @DisplayName("执行器不是 DisposableBean 时也能安全停止")
    void shouldToleratePlainExecutor() {
        NotifyTaskExecutor direct = NotifyTaskExecutor.direct();
        NotifyxLifecycle lifecycle = new NotifyxLifecycle(direct, List.of(channel), Duration.ZERO);
        lifecycle.start();

        lifecycle.stop();

        assertThat(channel.closed).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("phase 晚于 Web 优雅停机、早于普通 Bean 销毁")
    void shouldUseEarlyButSafePhase() {
        assertThat(NotifyxLifecycle.PHASE).isLessThan(NotifyxLifecycle.DEFAULT_PHASE);
    }

    private static PriorityNotifyExecutor executor() {
        return new PriorityNotifyExecutor(1, 1, 32, Duration.ofSeconds(1), "lifecycle-test-",
                NotifyMetrics.noop(), Duration.ofSeconds(5));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingChannel implements NotifyChannel {

        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final List<String> ignored = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            ignored.add(ctx.event().scene());
            return SendResult.success("1", null, Duration.ZERO);
        }

        @Override
        public void close() {
            closed.set(true);
            closeCount.incrementAndGet();
        }
    }
}
