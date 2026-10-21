package io.github.pkyit.notifyx.core.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.pkyit.notifyx.core.spi.DedupeStore;

/**
 * 内存去重存储（T0 梯度默认实现）。
 *
 * <p>条目数受 {@code maxEntries} 约束，达到上限时先清理过期条目，仍超限则拒绝新增占位，
 * 避免无界增长导致 OOM。</p>
 *
 * <p>@implNote 线程安全；{@code tryAcquire} 通过 {@link ConcurrentHashMap#compute} 保证原子性。</p>
 *
 * @since 1.0
 */
public final class MemoryDedupeStore implements DedupeStore {

    /** 默认条目上限。 */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final int maxEntries;
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();

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
        boolean[] acquired = {false};
        expiry.compute(dedupeKey, (key, current) -> {
            if (current != null && current > now) {
                return current;
            }
            if (current == null && expiry.size() >= maxEntries) {
                evictExpired(now);
                if (expiry.size() >= maxEntries) {
                    return null;
                }
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

    private int evictExpired(long now) {
        int before = expiry.size();
        expiry.entrySet().removeIf(entry -> entry.getValue() <= now);
        return before - expiry.size();
    }
}