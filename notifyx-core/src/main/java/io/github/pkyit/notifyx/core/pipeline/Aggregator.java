package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * T0 实例内聚合（设计书 §9.5）：把窗口内被去重的事件收拢成一条摘要。
 *
 * <p><b>能力边界必须诚实</b>：没有 Redis/MQ 时聚合只在<b>单实例内</b>生效，
 * 多实例部署下每个实例各发一条摘要；跨实例聚合属于 T1/T2（Redis {@code INCR} 或
 * consumer 侧聚合），不在本次实现范围。</p>
 *
 * <p>窗口结束时通过 {@link #sink(Consumer)} 注入的出口（通常是管线自身）投递一条摘要事件，
 * 摘要带唯一 {@code dedupeKey}，因此不会被去重吞掉。</p>
 *
 * <p>@implNote 线程安全；桶内计数在桶对象锁内完成，窗口结束由单线程调度器触发。
 * 关闭时会把剩余桶立即汇总，避免「关闭窗口内的聚合信息丢失」。</p>
 *
 * @since 1.0
 */
public final class Aggregator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

    private static final int MAX_SAMPLE_CHARS = 300;

    private final Config config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile Consumer<NotifyEvent> sink = event -> {
    };

    /**
     * 构造聚合器。
     *
     * @param config 配置
     */
    public Aggregator(Config config) {
        this.config = config == null ? Config.disabled() : config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notifyx-aggregator");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 设置摘要出口。
     *
     * @param sink 摘要消费者
     */
    public void sink(Consumer<NotifyEvent> sink) {
        if (sink != null) {
            this.sink = sink;
        }
    }

    /**
     * 记录一条被去重的事件。
     *
     * @param event 原始事件
     * @param level 生效级别（事件级别与场景级别取高）
     * @param dedupeKey 已计算的指纹
     * @param instanceId 当前实例
     */
    public void record(NotifyEvent event, Level level, String dedupeKey, String instanceId) {
        if (!config.enabled() || event == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = dedupeKey == null || dedupeKey.isBlank() ? event.eventId() : dedupeKey;
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(event.scene(), key, now));
        synchronized (bucket) {
            bucket.count++;
            if (level != null && (bucket.level == null || level.atLeast(bucket.level))) {
                bucket.level = level;
            }
            if (instanceId != null) {
                bucket.instances.add(instanceId);
            }
            String sample = sampleOf(event);
            if (bucket.firstSample == null) {
                bucket.firstSample = sample;
                bucket.firstAt = now;
            }
            bucket.lastSample = sample;
            bucket.lastAt = now;
        }
        schedule(key, bucket);
        evictIfNeeded();
    }

    private void schedule(String key, Bucket bucket) {
        if (!bucket.scheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(1L, config.window().toMillis());
        try {
            scheduler.schedule(() -> flush(key), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            log.debug("notifyx aggregator rejected schedule: {}", ex.toString());
        }
    }

    /**
     * 立即汇总所有未结束的桶（关闭时调用）。
     */
    public void flushAll() {
        for (String key : new ArrayList<>(buckets.keySet())) {
            flush(key);
        }
    }

    private void flush(String key) {
        Bucket bucket = buckets.remove(key);
        if (bucket == null) {
            return;
        }
        try {
            sink.accept(toSummary(bucket));
        } catch (RuntimeException ex) {
            log.warn("notifyx aggregator failed to emit summary for scene={}: {}", bucket.scene, ex.toString());
        }
    }

    private NotifyEvent toSummary(Bucket bucket) {
        long count;
        String firstSample;
        String lastSample;
        String scene;
        Level level;
        int instances;
        long firstAt;
        long lastAt;
        synchronized (bucket) {
            count = bucket.count;
            firstSample = bucket.firstSample;
            lastSample = bucket.lastSample;
            scene = bucket.scene;
            level = bucket.level == null ? Level.WARN : bucket.level;
            instances = bucket.instances.size();
            firstAt = bucket.firstAt;
            lastAt = bucket.lastAt;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("aggregateCount", count);
        params.put("aggregateScene", scene);
        params.put("aggregateWindowSeconds", config.window().toSeconds());
        params.put("aggregateFirstAt", Instant.ofEpochMilli(firstAt).toString());
        params.put("aggregateLastAt", Instant.ofEpochMilli(lastAt).toString());
        params.put("aggregateInstances", instances);
        if (firstSample != null) {
            params.put("aggregateFirstSample", firstSample);
        }
        if (lastSample != null) {
            params.put("aggregateLastSample", lastSample);
        }
        return NotifyEvent.builder(scene)
                .level(level)
                .title("【" + scene + "】聚合告警：" + count + " 条 / " + config.window().toSeconds() + "s")
                .dedupeKey("agg:" + bucket.key + ":" + firstAt)
                .params(params)
                .tag("aggregate", "true")
                .tag("aggregateCount", String.valueOf(count))
                .build();
    }

    private void evictIfNeeded() {
        if (buckets.size() <= config.maxEntries()) {
            return;
        }
        buckets.keySet().stream()
                .min(Comparator.comparingLong(key -> {
                    Bucket bucket = buckets.get(key);
                    return bucket == null ? Long.MAX_VALUE : bucket.firstAt;
                }))
                .ifPresent(this::flush);
    }

    private static String sampleOf(NotifyEvent event) {
        StringBuilder builder = new StringBuilder();
        if (event.bizId() != null) {
            builder.append("bizId=").append(event.bizId());
        }
        event.params().forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(key).append('=').append(value);
        });
        String sample = builder.toString();
        return sample.length() <= MAX_SAMPLE_CHARS ? sample : sample.substring(0, MAX_SAMPLE_CHARS) + "…";
    }

    @Override
    public void close() {
        try {
            flushAll();
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * 聚合配置。
     *
     * @param enabled 是否启用
     * @param window 窗口时长
     * @param maxEntries 桶上限
     * @since 1.0
     */
    public record Config(boolean enabled, Duration window, int maxEntries) {

        /**
         * 规范化。
         *
         * @param enabled 是否启用
         * @param window 窗口
         * @param maxEntries 桶上限
         */
        public Config {
            window = window == null || window.isNegative() || window.isZero()
                    ? Duration.ofSeconds(10) : window;
            maxEntries = Math.max(1, maxEntries);
        }

        /**
         * 默认关闭。
         *
         * @return 配置
         */
        public static Config disabled() {
            return new Config(false, Duration.ofSeconds(10), 10_000);
        }
    }

    /**
     * 聚合桶。
     *
     * @implNote 字段读写由桶对象锁保护。
     */
    private static final class Bucket {

        private final String scene;
        private final String key;
        private final Set<String> instances = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private int count;
        private Level level;
        private String firstSample;
        private String lastSample;
        private long firstAt;
        private long lastAt;

        private Bucket(String scene, String key, long createdAt) {
            this.scene = scene;
            this.key = key;
            this.firstAt = createdAt;
            this.lastAt = createdAt;
        }
    }
}
