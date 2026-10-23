package io.github.pkyit.notifyx.store.redis;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import io.github.pkyit.notifyx.core.spi.DedupeStore;

/**
 * Redis 分布式去重（设计书 §9.4）：{@code SET NX PX}，窗口内只放行一次。
 *
 * <p>{@code release} 对应发送失败时释放槽位；{@code size} 为避免 {@code KEYS} 扫描返回 -1。</p>
 *
 * <p>@implNote 依赖 {@link StringRedisTemplate}；Redis 故障由外层
 * {@link FallbackDedupeStore} 降级，不在本类吞异常。</p>
 *
 * @since 1.0
 */
public final class RedisDedupeStore implements DedupeStore {

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "return redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[1]) and 1 or 0", Long.class);

    private final StringRedisTemplate template;
    private final String keyPrefix;

    /**
     * 构造去重存储。
     *
     * @param template Redis 模板
     * @param keyPrefix 键前缀
     */
    public RedisDedupeStore(StringRedisTemplate template, String keyPrefix) {
        this.template = java.util.Objects.requireNonNull(template, "template");
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public boolean tryAcquire(String dedupeKey, Duration window) {
        long millis = Math.max(1L, window == null ? 1L : window.toMillis());
        Long result = template.execute(ACQUIRE, List.of(key(dedupeKey)), String.valueOf(millis));
        return result != null && result == 1L;
    }

    @Override
    public void release(String dedupeKey) {
        template.delete(key(dedupeKey));
    }

    /**
     * 诊断条目数；避免 KEYS 扫描，固定返回 -1 表示未知。
     *
     * @return -1
     */
    @Override
    public long size() {
        return -1L;
    }

    private String key(String dedupeKey) {
        return keyPrefix + "dedup:" + dedupeKey;
    }
}
