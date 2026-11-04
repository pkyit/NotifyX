package io.github.pkyit.notifyx.core.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.pkyit.notifyx.core.spi.DedupeStore;

/**
 * 内存去重存储（T0 梯度默认实现）。
 *
 * <p>条目数受 {@code maxEntries} 约束，达到上限时先清理过期条目，仍超限则拒绝新增占位，
 * 避免无界增长导致 OOM。</p>
 *
 * <p>容量打满时的过期清理按 {@link #EVICT_INTERVAL_MILLIS} 限频：同一时间窗内只有一个线程
 * 执行全表清理，其余调用直接按溢出策略拒绝，避免通知风暴下每次调用都全表扫描。</p>
 *
 * <p>@implNote 线程安全；{@code tryAcquire} 通过 {@link ConcurrentHashMap#compute} 保证原子性，
 * 清理逻辑不在 {@code compute} 回调内执行。</p>
 *
 * @since 1.0
 */
public final class MemoryDedupeStore implements DedupeStore {

    /** 默认条目上限。 */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /** 容量打满时全表清理的最小间隔（毫秒）。 */
    static final long EVICT_INTERVAL_MILLIS = 100L;

    private final int maxEntries;
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();
    private final AtomicLong lastEvictAt = new AtomicLong(0L);

    /**
     * 使用默认容量构造。
     */
    public MemoryDedupeStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * 构造去重存储。
     *
     * @param maxEntries 条目上限
     */
    public MemoryDedupeStore(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public boolean tryAcquire(String dedupeKey, Duration window) {
        long now = System.currentTimeMillis();
        long until = now + Math.max(0L, window.toMillis());
        // 快速路径：已存在且未过期 → 直接拒绝
        Long existing = expiry.get(dedupeKey);
        if (existing != null && existing > now) {
            return false;
        }
        // 容量检查 + 限频清理（在 compute 外部，避免死锁；限频避免风暴下反复全表扫描）
        if (expiry.size() >= maxEntries) {
            if (shouldEvict(now)) {
                evictExpired(now);
            }
            // 清理后仍超限（或本轮未执行清理）→ 按溢出策略拒绝，避免 OOM
            if (expiry.size() >= maxEntries) {
                return false;
            }
        }
        // 原子占位：compute 内只做占位，不再对其他条目做 removeIf
        boolean[] acquired = {false};
        expiry.compute(dedupeKey, (key, current) -> {
            if (current != null && current > now) {
                return current;
            }
            acquired[0] = true;
            return until;
        });
        return acquired[0];
    }

    @Override
    public void release(String dedupeKey) {
        expiry.remove(dedupeKey);
    }

    @Override
    public long size() {
        return expiry.size();
    }

    /**
     * 清理过期条目。
     *
     * @return 清理数量
     */
    public int evictExpired() {
        return evictExpired(System.currentTimeMillis());
    }

    private boolean shouldEvict(long now) {
        long last = lastEvictAt.get();
        if (now - last < EVICT_INTERVAL_MILLIS) {
            return false;
        }
        // 只有一个线程赢得清理权，其余线程直接走拒绝路径
        return lastEvictAt.compareAndSet(last, now);
    }

    private int evictExpired(long now) {
        int before = expiry.size();
        expiry.entrySet().removeIf(entry -> entry.getValue() <= now);
        return before - expiry.size();
    }
}
