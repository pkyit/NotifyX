package io.github.pkyit.notifyx.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * 优雅关闭（设计书 §9.10）：排空通知队列后再释放渠道资源。
 *
 * <p>为什么用 {@link SmartLifecycle} 而不是 {@code @PreDestroy}：{@code @PreDestroy}
 * 在 Bean 销毁阶段执行，此时数据源/Redis 连接可能已经关闭，落盘补偿会失败；
 * {@code SmartLifecycle.stop()} 在容器关闭早期执行，依赖仍然可用。</p>
 *
 * <p>phase 取 2147481599（{@code DEFAULT_PHASE - 2048}）：略低于 Web 容器的优雅停机阶段，
 * 也就是<b>先停止接收新请求、再排空通知队列</b>，最后才轮到数据源销毁。
 * 关闭期间新提交的事件会被执行器拒绝并记 warn——等失败落盘（D-003）落地后再改成落盘补偿。</p>
 *
 * <p>@implNote 线程安全；stop 幂等。</p>
 *
 * @since 1.0
 */
public final class NotifyxLifecycle implements SmartLifecycle {

    /** 生命周期阶段：晚于 Web 优雅停机，早于普通 Bean 销毁。 */
    public static final int PHASE = DEFAULT_PHASE - 2048;

    private static final Logger log = LoggerFactory.getLogger(NotifyxLifecycle.class);

    private final NotifyTaskExecutor executor;
    private final List<NotifyChannel> channels;
    private final Duration shutdownAwait;
    private final AutoCloseable aggregator;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 构造生命周期。
     *
     * @param executor 执行器
     * @param channels 渠道
     * @param shutdownAwait 排空等待时长
     */
    public NotifyxLifecycle(NotifyTaskExecutor executor, List<NotifyChannel> channels, Duration shutdownAwait) {
        this(executor, channels, shutdownAwait, null);
    }

    /**
     * 构造生命周期。
     *
     * @param executor 执行器
     * @param channels 渠道
     * @param shutdownAwait 排空等待时长
     * @param aggregator 聚合器（关闭时先汇总再停渠道），可为 null
     */
    public NotifyxLifecycle(NotifyTaskExecutor executor, List<NotifyChannel> channels, Duration shutdownAwait,
                            AutoCloseable aggregator) {
        this.executor = executor;
        this.channels = List.copyOf(channels);
        this.shutdownAwait = shutdownAwait == null ? Duration.ofSeconds(10) : shutdownAwait;
        this.aggregator = aggregator;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        log.info("notifyx shutting down: draining notifications (await={}s), channels={}",
                shutdownAwait.toSeconds(), channels.size());
        try {
            if (executor instanceof DisposableBean disposable) {
                disposable.destroy();
            }
        } catch (Exception ex) {
            log.warn("notifyx executor shutdown failed: {}", ex.toString());
        }
        if (aggregator != null) {
            try {
                // 先汇总剩余聚合桶：此时渠道还没关闭，摘要仍能发出去
                aggregator.close();
            } catch (Exception ex) {
                log.warn("notifyx aggregator close failed: {}", ex.toString());
            }
        }
        for (NotifyChannel channel : channels) {
            try {
                channel.close();
            } catch (RuntimeException ex) {
                log.warn("notifyx channel {} close failed: {}", channel.name(), ex.toString());
            }
        }
        log.info("notifyx shutdown finished");
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
