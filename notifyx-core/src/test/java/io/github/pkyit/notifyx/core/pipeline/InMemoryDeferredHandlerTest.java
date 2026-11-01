package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * T0 内存延后投递器测试（设计书 §9.3）：到期投递、顺序、溢出、关闭、无出口。
 *
 * @since 1.0.1
 */
class InMemoryDeferredHandlerTest {

    @Test
    @DisplayName("到期后事件经 sink 重新投递")
    void shouldDeliverWhenDue() {
        CountingMetrics metrics = new CountingMetrics();
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(10), metrics);
        List<NotifyEvent> delivered = new CopyOnWriteArrayList<>();
        handler.sink(delivered::add);

        handler.defer(NotifyEvent.builder("SCENE_A").build(), Instant.now().plusMillis(80));

        waitUntil(() -> delivered.size() == 1);
        assertThat(handler.pending()).isZero();
        assertThat(metrics.scheduled).hasSize(1);
        assertThat(metrics.delivered).containsExactly("SCENE_A");
        assertThat(metrics.dropped).isEmpty();
        handler.close();
    }

    @Test
    @DisplayName("早到期的事件先投递")
    void shouldDeliverInDueOrder() {
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(10), NotifyMetrics.noop());
        List<String> delivered = new CopyOnWriteArrayList<>();
        handler.sink(event -> delivered.add(event.scene()));

        handler.defer(NotifyEvent.builder("SCENE_LATE").build(), Instant.now().plusMillis(400));
        handler.defer(NotifyEvent.builder("SCENE_EARLY").build(), Instant.now().plusMillis(80));

        waitUntil(() -> delivered.size() == 2);
        assertThat(delivered).containsExactly("SCENE_EARLY", "SCENE_LATE");
        handler.close();
    }

    @Test
    @DisplayName("超过容量上限时丢弃并计数（overflow）")
    void shouldDropOnOverflow() {
        CountingMetrics metrics = new CountingMetrics();
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(1), metrics);
        handler.sink(event -> {
        });

        handler.defer(NotifyEvent.builder("SCENE_A").build(), Instant.now().plusSeconds(600));
        handler.defer(NotifyEvent.builder("SCENE_B").build(), Instant.now().plusSeconds(600));

        assertThat(handler.pending()).isEqualTo(1);
        assertThat(metrics.dropped).containsExactly("overflow:SCENE_B");
        handler.close();
    }

    @Test
    @DisplayName("未设置 sink 时到期事件按 no-sink 丢弃")
    void shouldDropWhenNoSink() {
        CountingMetrics metrics = new CountingMetrics();
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(10), metrics);

        handler.defer(NotifyEvent.builder("SCENE_A").build(), Instant.now().plusMillis(60));

        waitUntil(() -> metrics.dropped.contains("no-sink:SCENE_A"));
        handler.close();
    }

    @Test
    @DisplayName("sink 抛异常时事件按 sink-error 丢弃，不波及工作线程")
    void shouldSurviveSinkFailure() {
        CountingMetrics metrics = new CountingMetrics();
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(10), metrics);
        List<NotifyEvent> delivered = new CopyOnWriteArrayList<>();
        handler.sink(event -> {
            if (delivered.add(event) && delivered.size() == 1) {
                throw new IllegalStateException("boom");
            }
        });

        handler.defer(NotifyEvent.builder("SCENE_A").build(), Instant.now().plusMillis(60));
        handler.defer(NotifyEvent.builder("SCENE_B").build(), Instant.now().plusMillis(150));

        waitUntil(() -> delivered.size() == 2);
        assertThat(metrics.dropped).containsExactly("sink-error:SCENE_A");
        assertThat(metrics.delivered).containsExactly("SCENE_B");
        handler.close();
    }

    @Test
    @DisplayName("close 丢弃未到期事件并计数（shutdown），且幂等")
    void shouldDropPendingOnClose() throws InterruptedException {
        CountingMetrics metrics = new CountingMetrics();
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(10), metrics);
        List<NotifyEvent> delivered = new CopyOnWriteArrayList<>();
        handler.sink(delivered::add);

        handler.defer(NotifyEvent.builder("SCENE_A").build(), Instant.now().plusSeconds(600));
        handler.close();
        handler.close();
        handler.defer(NotifyEvent.builder("SCENE_B").build(), Instant.now().plusMillis(10));

        Thread.sleep(50);
        assertThat(handler.pending()).isZero();
        assertThat(delivered).isEmpty();
        assertThat(metrics.dropped).contains("shutdown:SCENE_A", "shutdown:SCENE_B");
    }

    @Test
    @DisplayName("close 与并发 defer 竞态：每条事件恰好计量一次，无滞留")
    void shouldAccountEveryEventUnderCloseRace() throws Exception {
        CountingMetrics metrics = new CountingMetrics();
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(50), metrics);
        handler.sink(event -> {
        });
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    handler.defer(NotifyEvent.builder("SCENE_RACE").build(), Instant.now().plusSeconds(600));
                }
                return null;
            }));
        }
        start.countDown();
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            handler.close();
        });
        closer.start();
        for (Future<Object> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        closer.join(5_000);

        // 不变量：每条事件要么在入队时计量 scheduled，要么被 drop（overflow/shutdown）；
        // 未到期事件不会投递，因此 close 结束后 drop 总数必须等于事件总数，队列必须清空
        assertThat(handler.pending()).isZero();
        assertThat(metrics.dropped).hasSize(total);
        assertThat(metrics.dropped).allMatch(reason ->
                reason.startsWith("shutdown:") || reason.startsWith("overflow:"));
        pool.shutdownNow();
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", ex);
            }
        }
    }

    private static final class CountingMetrics implements NotifyMetrics {

        private final List<String> scheduled = new CopyOnWriteArrayList<>();
        private final List<String> delivered = new CopyOnWriteArrayList<>();
        private final List<String> dropped = new CopyOnWriteArrayList<>();

        @Override
        public void deferredScheduled(String scene) {
            scheduled.add(scene);
        }

        @Override
        public void deferredDelivered(String scene) {
            delivered.add(scene);
        }

        @Override
        public void deferredDropped(String reason, String scene) {
            dropped.add(reason + ":" + scene);
        }
    }
}
