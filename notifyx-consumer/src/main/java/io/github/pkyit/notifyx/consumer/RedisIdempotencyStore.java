package io.github.pkyit.notifyx.consumer;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 幂等（T1，设计书 §11.4）：{@code SET NX EX 86400}。
 *
 * <p>Redis 异常时降级到内存幂等：可能重复发送，但不会因为幂等存储故障而丢通知。</p>
 *
 * @since 1.0
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String PREFIX = "nx:idem:";

    private final StringRedisTemplate template;
    private final Duration ttl;
    private final MemoryIdempotencyStore fallback;

    /**
     * 构造存储。
     *
     * @param template Redis 模板
     * @param ttl TTL
     * @param maxEntries 降级内存容量
     */
    public RedisIdempotencyStore(StringRedisTemplate template, Duration ttl, int maxEntries) {
        this.template = java.util.Objects.requireNonNull(template, "template");
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(24) : ttl;
        this.fallback = new MemoryIdempotencyStore(this.ttl.toMillis(), maxEntries);
    }

    @Override
    public boolean firstSeen(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        try {
            Boolean inserted = template.opsForValue().setIfAbsent(PREFIX + key, "1", ttl);
            return Boolean.TRUE.equals(inserted);
        } catch (RuntimeException ex) {
            log.warn("notifyx redis idempotency unavailable, degrade to memory: {}", ex.toString());
            return fallback.firstSeen(key);
        }
    }
}
