package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * Redis 滑动窗限流（设计书 §9.4）：ZSET 记录时间戳，Lua 保证「清理 + 判断 + 写入」原子。
 *
 * <p>成员使用 {@code nowMs:count}，避免 {@code math.random()} 的不确定性与碰撞。</p>
 *
 * <p>@implNote 依赖 {@link StringRedisTemplate}；Redis 故障由外层
 * {@link FallbackRateLimiter} 降级。</p>
 *
 * @since 1.0
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
            local c = redis.call('ZCARD', KEYS[1])
            if c >= tonumber(ARGV[3]) then
                return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1] .. ':' .. c)
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) + 1000)
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate template;
    private final String keyPrefix;

    /**
     * 构造限流器。
     *
     * @param template Redis 模板
     * @param keyPrefix 键前缀
     */
    public RedisRateLimiter(StringRedisTemplate template, String keyPrefix) {
        this.template = java.util.Objects.requireNonNull(template, "template");
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public boolean tryAcquire(String key, int permits, Duration window) {
        if (permits <= 0) {
            return false;
        }
        long windowMillis = Math.max(1L, window.toMillis());
        Long result = template.execute(ACQUIRE, List.of(key(key)),
                String.valueOf(System.currentTimeMillis()), String.valueOf(windowMillis), String.valueOf(permits));
        return result != null && result == 1L;
    }

    @Override
    public long currentCount(String key, Duration window) {
        long windowMillis = Math.max(1L, window.toMillis());
        Long result = template.execute(COUNT, List.of(key(key)),
                String.valueOf(System.currentTimeMillis()), String.valueOf(windowMillis));
        return result == null ? 0L : result;
    }

    private String key(String key) {
        return keyPrefix + "lim:" + key;
    }
}
