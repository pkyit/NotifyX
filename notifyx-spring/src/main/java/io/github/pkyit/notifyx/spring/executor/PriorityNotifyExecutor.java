package io.github.pkyit.notifyx.spring.executor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;

/**
 * 优先级投递队列（设计书 ADR-008 / 9.11）。
 *
 * <p>队列按「级别降序 + 入队时间升序」排序；队列达到软上限时丢弃优先级最低的任务
 * 并计入 {@code queue.discarded}，绝不使用 {@code CallerRunsPolicy}。</p>
 *
 * <p>@implNote 线程安全。</p>
 *
 * @since 1.0
 */
public final class PriorityNotifyExecutor implements NotifyTaskExecutor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PriorityNotifyExecutor.class);

    private final PriorityBlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor pool;
    private final int capacity;
    private final NotifyMetrics metrics;
    private final AtomicLong sequence = new AtomicLong();
    private final Duration shutdownAwait;

    /**
     * 构造执行器。
     *
     * @param corePoolSize 常驻线程数
     * @param maxPoolSize 最大线程数（受无界优先级队列限制，实际等于常驻数）
     * @param capacity 队列软上限
     * @param keepAlive 空闲线程存活时间
     * @param threadNamePrefix 线程名前缀
     * @param metrics 指标出口
     * @param shutdownAwait 优雅关闭等待时长
     */
    public PriorityNotifyExecutor(int corePoolSize, int maxPoolSize, int capacity, Duration keepAlive,
                                  String threadNamePrefix, NotifyMetrics metrics, Duration shutdownAwait) {
        this.capacity = Math.max(1, capacity);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.shutdownAwait = shutdownAwait == null ? Duration.ofSeconds(10) : shutdownAwait;
        this.queue = new PriorityBlockingQueue<>(Math.min(this.capacity, 1024),
                (left, right) -> ((PriorityTask) left).compareTo((PriorityTask) right));
        this.pool = new ThreadPoolExecutor(
                Math.max(1, corePoolSize),
                Math.max(Math.max(1, corePoolSize), maxPoolSize),
                keepAlive == null ? 60_000L : keepAlive.toMillis(),
                TimeUnit.MILLISECONDS,
                queue,
                new NamedThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(false);
    }

    @Override
    public void execute(Level level, String taskKey, Runnable task) {
        Objects.requireNonNull(task, "task");
        PriorityTask prioritized = new PriorityTask(level, sequence.incrementAndGet(), taskKey, task);
        if (queue.size() >= capacity) {
            Runnable lowest = lowestPriority();
            if (lowest instanceof PriorityTask lowTask && lowTask.compareTo(prioritized) > 0) {
                if (queue.remove(lowest)) {
                    metrics.queueDiscarded("replaced-by-higher-priority", lowTask.level());
                }
            } else {
                metrics.queueDiscarded("queue-full", level);
                log.warn("notifyx queue full, discarding event level={} key={}", level, taskKey);
                return;
            }
        }
        try {
            pool.execute(prioritized);
        } catch (RejectedExecutionException ex) {
            metrics.queueDiscarded("executor-rejected", level);
            log.warn("notifyx executor rejected event key={}: {}", taskKey, ex.toString());
        }
    }

    private Runnable lowestPriority() {
        Runnable lowest = null;
        for (Runnable runnable : queue) {
            if (lowest == null) {
                lowest = runnable;
            } else if (runnable instanceof PriorityTask candidate && lowest instanceof PriorityTask current
                    && candidate.compareTo(current) > 0) {
                lowest = runnable;
            }
        }
        return lowest;
    }

    /**
     * 队列水位。
     *
     * @return 队列长度
     */
    public int queueSize() {
        return queue.size();
    }

    /**
     * 队列软上限。
     *
     * @return 容量
     */
    public int capacity() {
        return capacity;
    }

    @Override
    public void destroy() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(shutdownAwait.toMillis(), TimeUnit.MILLISECONDS)) {
                int remaining = pool.shutdownNow().size();
                log.warn("notifyx shutdown timeout, {} tasks dropped from queue", remaining);
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 优先级任务：级别降序，同级按入队序号升序。
     *
     * <p>@implNote 不可变，线程安全。</p>
     *
     * @since 1.0
     */
    static final class PriorityTask implements Runnable, Comparable<PriorityTask> {

        private final Level level;
        private final long sequence;
        private final String taskKey;
        private final Runnable delegate;

        PriorityTask(Level level, long sequence, String taskKey, Runnable delegate) {
            this.level = level == null ? Level.WARN : level;
            this.sequence = sequence;
            this.taskKey = taskKey;
            this.delegate = delegate;
        }

        Level level() {
            return level;
        }

        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PriorityTask other) {
            int byLevel = Integer.compare(other.level.ordinal(), this.level.ordinal());
            return byLevel != 0 ? byLevel : Long.compare(this.sequence, other.sequence);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix == null || prefix.isBlank() ? "notifyx-" : prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }
}
