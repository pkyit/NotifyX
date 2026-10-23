package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.pipeline.EventAggregator;

/**
 * T1 跨实例聚合（设计书 §9.5）：Redis Hash 累计计数与样本，窗口结束时用
 * {@code SET NX} 抢锁，只有一个实例发送摘要。
 *
 * <p>键结构：{@code nx:agg:<dedupeKey>}（Hash）、{@code nx:agg:inst:<dedupeKey>}（实例集合）、
 * {@code nx:agg:lock:<dedupeKey>}（摘要锁）。</p>
 *
 * <p><b>边界</b>：若所有记录该窗口的实例都在窗口结束前崩溃，摘要会随 TTL 丢失；
 * 每实例只调度本地见过的事件，不做 SCAN 扫描。</p>
 *
 * <p>@implNote Redis 异常只 warn，不向管线抛；聚合是旁路。</p>
 *
 * @since 1.0
 */
public final class RedisAggregator implements EventAggregator {

    private static final Logger log = LoggerFactory.getLogger(RedisAggregator.class);
    private static final int MAX_SAMPLE_CHARS = 300;

    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>("""
            local c = redis.call('HINCRBY', KEYS[1], 'count', 1)
            if c == 1 then
                redis.call('HSET', KEYS[1], 'scene', ARGV[1], 'firstAt', ARGV[2], 'firstSample', ARGV[3])
            end
            redis.call('HSET', KEYS[1], 'lastAt', ARGV[2], 'lastSample', ARGV[3])
            redis.call('HSETNX', KEYS[1], 'level', ARGV[4])
            redis.call('SADD', KEYS[2], ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            redis.call('PEXPIRE', KEYS[2], ARGV[6])
            return c
            """, Long.class);

    private static final DefaultRedisScript<Long> LOCK_RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate template;
    private final String keyPrefix;
    private final Config config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> scheduled = new ConcurrentHashMap<>();
    private volatile Consumer<NotifyEvent> sink = event -> {
    };

    /**
     * 构造 Redis 聚合器。
     *
     * @param template Redis 模板
     * @param keyPrefix 键前缀
     * @param config 配置
     */
    public RedisAggregator(StringRedisTemplate template, String keyPrefix, Config config) {
        this.template = java.util.Objects.requireNonNull(template, "template");
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.config = config == null ? Config.disabled() : config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notifyx-redis-aggregator");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void sink(Consumer<NotifyEvent> sink) {
        if (sink != null) {
            this.sink = sink;
        }
    }

    @Override
    public void record(NotifyEvent event, Level level, String dedupeKey, String instanceId) {
        if (!config.enabled() || event == null) {
            return;
        }
        String key = dedupeKey == null || dedupeKey.isBlank() ? event.eventId() : dedupeKey;
        long now = System.currentTimeMillis();
        long ttlMillis = config.window().toMillis() * 2 + 5000L;
        try {
            Long count = template.execute(RECORD, List.of(hashKey(key), instancesKey(key)),
                    event.scene(), String.valueOf(now), sampleOf(event),
                    level == null ? Level.WARN.name() : level.name(),
                    instanceId == null ? "" : instanceId, String.valueOf(ttlMillis));
            if (count == null || count <= 0) {
                return;
            }
            schedule(key);
        } catch (RuntimeException ex) {
            log.warn("notifyx redis aggregation record failed: {}", ex.toString());
        }
    }

    private void schedule(String key) {
        long now = System.currentTimeMillis();
        if (scheduled.putIfAbsent(key, now) != null) {
            return;
        }
        if (scheduled.size() > config.maxEntries()) {
            String oldest = scheduled.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest != null) {
                flush(oldest);
            }
        }
        try {
            scheduler.schedule(() -> flush(key), Math.max(1L, config.window().toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            scheduled.remove(key);
            log.debug("notifyx redis aggregation schedule rejected: {}", ex.toString());
        }
    }

    @Override
    public void flushAll() {
        for (String key : List.copyOf(scheduled.keySet())) {
            flush(key);
        }
    }

    private void flush(String key) {
        scheduled.remove(key);
        String token = UUID.randomUUID().toString();
        try {
            Boolean locked = template.opsForValue().setIfAbsent(lockKey(key), token, lockTtl());
            if (!Boolean.TRUE.equals(locked)) {
                return;
            }
            try {
                Map<Object, Object> hash = template.opsForHash().entries(hashKey(key));
                long count = asLong(hash.get("count"));
                if (count <= 0) {
                    template.delete(instancesKey(key));
                    return;
                }
                String scene = String.valueOf(hash.getOrDefault("scene", "UNKNOWN"));
                long firstAt = asLong(hash.get("firstAt"));
                long lastAt = asLong(hash.get("lastAt"));
                Level level = asLevel(hash.get("level"));
                Long instances = template.opsForSet().size(instancesKey(key));

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("aggregateCount", count);
                params.put("aggregateScene", scene);
                params.put("aggregateWindowSeconds", config.window().toSeconds());
                params.put("aggregateFirstAt", Instant.ofEpochMilli(firstAt).toString());
                params.put("aggregateLastAt", Instant.ofEpochMilli(lastAt).toString());
                params.put("aggregateInstances", instances == null ? 0 : instances.intValue());
                if (hash.get("firstSample") != null) {
                    params.put("aggregateFirstSample", String.valueOf(hash.get("firstSample")));
                }
                if (hash.get("lastSample") != null) {
                    params.put("aggregateLastSample", String.valueOf(hash.get("lastSample")));
                }
                NotifyEvent summary = NotifyEvent.builder(scene)
                        .level(level)
                        .title("【" + scene + "】聚合告警：" + count + " 条 / " + config.window().toSeconds() + "s")
                        .dedupeKey("agg:" + key + ":" + firstAt)
                        .params(params)
                        .tag("aggregate", "true")
                        .tag("aggregateCount", String.valueOf(count))
                        .build();
                template.delete(List.of(hashKey(key), instancesKey(key)));
                sink.accept(summary);
            } finally {
                releaseLock(key, token);
            }
        } catch (RuntimeException ex) {
            log.warn("notifyx redis aggregation flush failed: {}", ex.toString());
        }
    }

    private void releaseLock(String key, String token) {
        try {
            template.execute(LOCK_RELEASE, List.of(lockKey(key)), token);
        } catch (RuntimeException ex) {
            log.debug("notifyx redis aggregation lock release failed: {}", ex.toString());
        }
    }

    private Duration lockTtl() {
        return config.window().multipliedBy(2).plusSeconds(5);
    }

    private String hashKey(String key) {
        return keyPrefix + "agg:" + key;
    }

    private String instancesKey(String key) {
        return keyPrefix + "agg:inst:" + key;
    }

    private String lockKey(String key) {
        return keyPrefix + "agg:lock:" + key;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static Level asLevel(Object value) {
        if (value == null) {
            return Level.WARN;
        }
        try {
            return Level.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return Level.WARN;
        }
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
     * @param window 窗口
     * @param maxEntries 本地待调度键上限
     * @since 1.0
     */
    public record Config(boolean enabled, Duration window, int maxEntries) {

        /**
         * 规范化。
         *
         * @param enabled 启用
         * @param window 窗口
         * @param maxEntries 上限
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
}
