package io.github.pkyit.notifyx.core.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * T0 内存延后投递器（设计书 §9.3）：免打扰时段命中的事件暂存到窗口结束后再重新进入管线。
 *
 * <p><b>能力边界必须诚实</b>：队列在 JVM 内存中，<b>重启即丢失</b>；持久化延后属于
 * T1+（Redis/JDBC）能力。队列有容量上限，溢出时丢弃并计数——内存不是可靠存储。</p>
 *
 * <p>到期事件通过 {@link #sink(Consumer)} 注入的出口（通常是管线自身）重新投递。
 * 重新投递会完整走一遍过滤链：此时免打扰窗口已结束会放行，去重/限流/聚合照常生效，
 * 因此夜间堆积的同指纹事件在窗口结束时会被去重收拢，不会刷屏。</p>
 *
 * <p>@implNote 线程安全；单守护线程消费 {@link DelayQueue}，{@link #close()} 幂等。
 * 关闭时未到期事件计入 {@code deferredDropped(reason=shutdown)}，不会在静默时段内发出。</p>
 *
 * @since 1.0.1
 */
public final class InMemoryDeferredHandler implements DeferredHandler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeferredHandler.class);

    private final Config config;
    private final NotifyMetrics metrics;
    private final DelayQueue<Task> queue = new DelayQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 串行化 defer 的「检查+入队」与 close 的「清点+清空」，保证每条事件恰好计量一次。 */
    private final Object lock = new Object();
    private final Thread worker;
    private volatile Consumer<NotifyEvent> sink;

    /**
     * 构造延后投递器。
     *
     * @param config 配置，null 回退默认配置
     * @param metrics 指标出口，null 回退 no-op
     */
    public InMemoryDeferredHandler(Config config, NotifyMetrics metrics) {
        this.config = config == null ? Config.defaults() : config;
        this.metrics = metrics == null ? NotifyMetrics.noop() : metrics;
        this.worker = new Thread(this::runLoop, "notifyx-deferred");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 设置到期出口（通常是 {@code pipeline::handle}）。
     *
     * @param sink 出口，null 忽略
     */
    public void sink(Consumer<NotifyEvent> sink) {
        if (sink != null) {
            this.sink = sink;
        }
    }

    @Override
    public void defer(NotifyEvent event, Instant until) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(until, "until");
        synchronized (lock) {
            if (closed.get()) {
                metrics.deferredDropped("shutdown", event.scene());
                log.debug("notifyx deferred handler closed, drop scene={}", event.scene());
                return;
            }
            if (queue.size() >= config.maxEntries()) {
                metrics.deferredDropped("overflow", event.scene());
                log.warn("notifyx deferred queue overflow (max={}), drop scene={} eventId={}",
                        config.maxEntries(), event.scene(), event.eventId());
                return;
            }
            queue.offer(new Task(event, until.toEpochMilli()));
            metrics.deferredScheduled(event.scene());
            metrics.deferredPending(queue.size());
        }
        log.debug("notifyx event deferred until {} scene={} eventId={}", until, event.scene(), event.eventId());
    }

    /**
     * 当前待投递数量（诊断与测试用）。
     *
     * @return 队列长度
     */
    public int pending() {
        return queue.size();
    }

    private void runLoop() {
        while (!closed.get()) {
            try {
                Task task = queue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                deliver(task);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.warn("notifyx deferred loop error: {}", ex.toString());
            }
        }
    }

    private void deliver(Task task) {
        Consumer<NotifyEvent> consumer = sink;
        if (consumer == null) {
            metrics.deferredDropped("no-sink", task.event.scene());
            log.warn("notifyx deferred event due but no sink wired, drop scene={}", task.event.scene());
            return;
        }
        try {
            consumer.accept(task.event);
            metrics.deferredDelivered(task.event.scene());
        } catch (RuntimeException ex) {
            metrics.deferredDropped("sink-error", task.event.scene());
            log.warn("notifyx deferred sink failed for scene={}: {}", task.event.scene(), ex.toString());
        } finally {
            metrics.deferredPending(queue.size());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.interrupt();
        // DelayQueue 的 drainTo/poll 只处理已到期元素，必须整体搬空再清空；
        // 与 defer 共用锁，防止「defer 在清空之后入队」导致事件滞留且无计量
        List<Task> remaining;
        synchronized (lock) {
            remaining = new ArrayList<>(queue);
            queue.clear();
        }
        for (Task task : remaining) {
            metrics.deferredDropped("shutdown", task.event.scene());
        }
        metrics.deferredPending(0);
        if (!remaining.isEmpty()) {
            log.warn("notifyx deferred handler closed, {} pending event(s) dropped (T0 in-memory)", remaining.size());
        }
    }

    /**
     * 延后队列配置。
     *
     * @param maxEntries 队列容量上限，溢出丢弃并计数
     * @since 1.0.1
     */
    public record Config(int maxEntries) {

        /** 默认容量。 */
        public static final int DEFAULT_MAX_ENTRIES = 10_000;

        /**
         * 规范化。
         *
         * @param maxEntries 容量上限
         */
        public Config {
            maxEntries = Math.max(1, maxEntries);
        }

        /**
         * 默认配置。
         *
         * @return 配置
         */
        public static Config defaults() {
            return new Config(DEFAULT_MAX_ENTRIES);
        }
    }

    /**
     * 延后任务。
     *
     * @implNote 不可变，{@link Delayed} 到期语义由 {@code untilMillis} 决定。
     */
    private static final class Task implements Delayed {

        private final NotifyEvent event;
        private final long untilMillis;

        private Task(NotifyEvent event, long untilMillis) {
            this.event = event;
            this.untilMillis = untilMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(untilMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
