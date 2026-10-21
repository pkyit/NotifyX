package io.github.pkyit.notifyx.spring.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.Level;

/**
 * 优先级执行器测试（ADR-008 / §9.11）：
 * 级别降序执行、队列满丢最低、同级新任务丢弃、关闭后拒绝且不抛。
 *
 * @since 1.0
 */
class PriorityNotifyExecutorTest {

    private final List<String> executed = new CopyOnWriteArrayList<>();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private PriorityNotifyExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    @DisplayName("按级别降序执行（CRITICAL → WARN → INFO）")
    void shouldRunByPriority() throws Exception {
        executor = newExecutor(1, 16);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(Level.INFO, "blocker", () -> {
            blockerStarted.countDown();
            await(release);
            executed.add("blocker");
        });
        assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        executor.execute(Level.INFO, "info", () -> executed.add("info"));
        executor.execute(Level.WARN, "warn", () -> executed.add("warn"));
        executor.execute(Level.CRITICAL, "critical", () -> executed.add("critical"));
        release.countDown();

        awaitExecuted(4);
        assertThat(executed).containsExactly("blocker", "critical", "warn", "info");
    }

    @Test
    @DisplayName("队列满时用高优先级替换最低优先级，并计数")
    void shouldReplaceLowestPriority() throws Exception {
        executor = newExecutor(1, 2);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(Level.INFO, "blocker", () -> {
            blockerStarted.countDown();
            await(release);
            executed.add("blocker");
        });
        assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        executor.execute(Level.WARN, "warn-a", () -> executed.add("warn-a"));
        executor.execute(Level.WARN, "warn-b", () -> executed.add("warn-b"));
        executor.execute(Level.CRITICAL, "critical", () -> executed.add("critical"));
        release.countDown();

        awaitExecuted(3);
        assertThat(executed).containsExactly("blocker", "critical", "warn-a");
        assertThat(metrics.discardedReasons).contains("replaced-by-higher-priority");
    }

    @Test
    @DisplayName("队列满且新任务不更高时丢弃新任务，计数 queue-full")
    void shouldDiscardNewTaskWhenNotHigher() throws Exception {
        executor = newExecutor(1, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(Level.INFO, "blocker", () -> {
            blockerStarted.countDown();
            await(release);
            executed.add("blocker");
        });
        assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        executor.execute(Level.WARN, "warn-a", () -> executed.add("warn-a"));
        executor.execute(Level.WARN, "warn-b", () -> executed.add("warn-b"));
        release.countDown();

        awaitExecuted(2);
        assertThat(executed).containsExactly("blocker", "warn-a");
        assertThat(metrics.discardedReasons).contains("queue-full");
    }

    @Test
    @DisplayName("关闭后提交任务不抛异常，计入 executor-rejected")
    void shouldRejectAfterShutdownWithoutThrowing() {
        executor = newExecutor(1, 4);
        executor.destroy();

        executor.execute(Level.ERROR, "late", () -> executed.add("late"));

        assertThat(executed).isEmpty();
        assertThat(metrics.discardedReasons).contains("executor-rejected");
    }

    @Test
    @DisplayName("队列水位与软上限可读")
    void shouldExposeQueueStats() {
        executor = newExecutor(1, 8);

        assertThat(executor.capacity()).isEqualTo(8);
        assertThat(executor.queueSize()).isZero();
    }

    private PriorityNotifyExecutor newExecutor(int corePoolSize, int capacity) {
        return new PriorityNotifyExecutor(corePoolSize, corePoolSize, capacity, Duration.ofSeconds(5),
                "test-executor-", metrics, Duration.ofSeconds(2));
    }

    private void awaitExecuted(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (executed.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(executed).hasSize(count);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingMetrics implements NotifyMetrics {

        private final List<String> discardedReasons = new CopyOnWriteArrayList<>();

        @Override
        public void queueDiscarded(String reason, Level level) {
            discardedReasons.add(reason);
        }
    }
}
