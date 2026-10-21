package io.github.pkyit.notifyx.autoconfigure;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;

/**
 * 告警投递线程池。
 *
 * <p>队列满时使用 {@link ThreadPoolExecutor.CallerRunsPolicy} 做背压：宁可让调用方慢一点，
 * 也不静默丢弃告警。{@code notifyx.async.enabled=false} 时退化为调用方线程同步执行。</p>
 */
public final class AlertExecutor implements Executor, DisposableBean {

    private final ExecutorService service;

    private AlertExecutor(ExecutorService service) {
        this.service = service;
    }

    /**
     * 创建异步执行器。
     */
    public static AlertExecutor async(AlertProperties.Async config) {
        int core = Math.max(1, config.getCorePoolSize());
        int max = Math.max(core, config.getMaxPoolSize());
        int queue = Math.max(1, config.getQueueCapacity());

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                core,
                max,
                Math.max(0, config.getKeepAlive().toMillis()),
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue),
                new NamedThreadFactory(config.getThreadNamePrefix()),
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(false);
        return new AlertExecutor(pool);
    }

    /**
     * 同步执行器，用于关闭异步或测试。
     */
    public static AlertExecutor direct() {
        return new AlertExecutor(null);
    }

    @Override
    public void execute(Runnable task) {
        if (service == null) {
            task.run();
        } else {
            service.execute(task);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        if (service == null) {
            return;
        }
        service.shutdown();
        if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
            service.shutdownNow();
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
            thread.setDaemon(true);
            return thread;
        }
    }
}
