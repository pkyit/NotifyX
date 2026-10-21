package io.github.pkyit.notifyx.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.pipeline.DefaultNotifyKit;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.RecordStatus;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * 失败记录自动补偿（设计书 §9.9 状态机）。
 *
 * <p>定时扫描 {@link FailureStore#loadPending(int)}，对每条记录：</p>
 * <ol>
 *   <li>乐观锁 CAS 到 {@code SENDING}（多实例扫同一目录时只有一个能抢到）；</li>
 *   <li>解码 {@link NotifyEnvelope}，失败则直接进入 {@code DEAD}（人工介入）；</li>
 *   <li>同步重投：成功 → {@code SENT}；失败 → 未达上限用 {@code requeue} 退避，达到上限 → {@code DEAD}。</li>
 * </ol>
 *
 * <p><b>单实例问题</b>：默认每个实例都扫，靠乐观锁去重（§9.9 方案 A），低频兜底路径可接受。</p>
 *
 * <p>@implNote 调度线程为 daemon；{@link #close()} 幂等。{@code enabled=false} 时不启动调度，
 * 但 {@link #runOnce()} 仍可手动触发（便于测试与运维补跑）。</p>
 *
 * @since 1.0
 */
public final class FailureCompensationRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FailureCompensationRunner.class);

    private final NotifyKit notifyKit;
    private final FailureStore failureStore;
    private final Config config;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 构造补偿器。
     *
     * @param notifyKit 门面
     * @param failureStore 失败存储
     * @param config 配置
     */
    public FailureCompensationRunner(NotifyKit notifyKit, FailureStore failureStore, Config config) {
        this.notifyKit = java.util.Objects.requireNonNull(notifyKit, "notifyKit");
        this.failureStore = java.util.Objects.requireNonNull(failureStore, "failureStore");
        this.config = config == null ? Config.disabled() : config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notifyx-compensation");
            thread.setDaemon(true);
            return thread;
        });
        if (this.config.enabled()) {
            start();
        }
    }

    private void start() {
        long interval = Math.max(1000L, config.interval().toMillis());
        try {
            scheduler.scheduleWithFixedDelay(this::runOnceQuietly, interval, interval, TimeUnit.MILLISECONDS);
            running.set(true);
            log.info("notifyx compensation started: interval={}s, batchSize={}, maxAttempts={}",
                    config.interval().toSeconds(), config.batchSize(), config.maxAttempts());
        } catch (RejectedExecutionException ex) {
            log.warn("notifyx compensation schedule rejected: {}", ex.toString());
        }
    }

    /**
     * 手动执行一轮补偿。
     *
     * @return 实际处理的记录数
     */
    public int runOnce() {
        List<FailedRecord> pending;
        try {
            pending = failureStore.loadPending(config.batchSize());
        } catch (RuntimeException ex) {
            log.warn("notifyx compensation load pending failed: {}", ex.toString());
            return 0;
        }
        int processed = 0;
        for (FailedRecord record : pending) {
            if (compensate(record)) {
                processed++;
            }
        }
        return processed;
    }

    private void runOnceQuietly() {
        try {
            int processed = runOnce();
            if (processed > 0) {
                log.info("notifyx compensation processed {} record(s)", processed);
            }
        } catch (RuntimeException ex) {
            log.warn("notifyx compensation round failed: {}", ex.toString());
        }
    }

    private boolean compensate(FailedRecord record) {
        int attempts = record.attempts() + 1;
        if (!failureStore.updateStatus(record.recordId(), record.version(), RecordStatus.SENDING, attempts)) {
            // 被其它实例抢走或版本已变，跳过
            return false;
        }
        NotifyEnvelope envelope;
        try {
            envelope = NotifyEnvelopeCodec.fromJson(record.envelopeJson());
        } catch (RuntimeException ex) {
            failureStore.updateStatus(record.recordId(), record.version() + 1, RecordStatus.DEAD, attempts);
            log.error("notifyx compensation cannot decode envelope, moved to DEAD recordId={} reason={}",
                    record.recordId(), ex.toString());
            return true;
        }
        try {
            boolean delivered = deliver(envelope);
            if (delivered) {
                failureStore.updateStatus(record.recordId(), record.version() + 1, RecordStatus.SENT, attempts);
                log.info("notifyx compensation delivered recordId={} scene={} attempts={}",
                        record.recordId(), envelope.scene(), attempts);
                return true;
            }
        } catch (Throwable ex) {
            log.warn("notifyx compensation delivery failed recordId={}: {}", record.recordId(), ex.toString());
        }
        if (attempts >= config.maxAttempts()) {
            failureStore.updateStatus(record.recordId(), record.version() + 1, RecordStatus.DEAD, attempts);
            log.error("notifyx compensation moved to DEAD recordId={} scene={} attempts={}",
                    record.recordId(), envelope.scene(), attempts);
        } else {
            long nextRetryAt = System.currentTimeMillis() + config.retryBackoff().toMillis();
            failureStore.requeue(record.recordId(), record.version() + 1, attempts, nextRetryAt);
            log.warn("notifyx compensation requeued recordId={} attempts={} nextRetryAt={}",
                    record.recordId(), attempts, nextRetryAt);
        }
        return true;
    }

    private boolean deliver(NotifyEnvelope envelope) {
        io.github.pkyit.notifyx.core.model.NotifyEvent event = envelope.toEvent();
        if (notifyKit instanceof DefaultNotifyKit defaultKit) {
            return defaultKit.deliverSync(event);
        }
        // 自定义 NotifyKit：没有同步结果，按「提交成功」处理（建议实现方提供同步语义）
        notifyKit.send(event);
        return true;
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
    }

    /**
     * 是否正在定时调度。
     *
     * @return 是否运行中
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 补偿配置。
     *
     * @param enabled 是否启用定时补偿
     * @param interval 扫描间隔
     * @param batchSize 每轮上限
     * @param maxAttempts 最大尝试次数
     * @param retryBackoff 失败退避
     * @since 1.0
     */
    public record Config(boolean enabled, Duration interval, int batchSize, int maxAttempts, Duration retryBackoff) {

        /**
         * 规范化。
         *
         * @param enabled 启用
         * @param interval 间隔
         * @param batchSize 批量
         * @param maxAttempts 最大尝试
         * @param retryBackoff 退避
         */
        public Config {
            interval = interval == null || interval.isNegative() || interval.isZero()
                    ? Duration.ofSeconds(60) : interval;
            batchSize = Math.max(1, batchSize);
            maxAttempts = Math.max(1, maxAttempts);
            retryBackoff = retryBackoff == null || retryBackoff.isNegative() ? Duration.ofMinutes(5) : retryBackoff;
        }

        /**
         * 默认关闭。
         *
         * @return 配置
         */
        public static Config disabled() {
            return new Config(false, Duration.ofSeconds(60), 50, 5, Duration.ofMinutes(5));
        }
    }
}
