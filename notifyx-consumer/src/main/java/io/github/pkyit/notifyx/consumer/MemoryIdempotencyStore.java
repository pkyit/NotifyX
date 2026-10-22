package io.github.pkyit.notifyx.consumer;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * consumer 幂等存储（T0 内存版）：TTL + 容量上限。
 *
 * <p>重启会丢失，文档需警告「无持久化幂等存储时重启可能重复发送」（设计书 §11.4）。</p>
 *
 * <p>@implNote 线程安全；过期清理是惰性的，避免额外调度线程。</p>
 *
 * @since 1.0
 */
public final class MemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
    private final long ttlMillis;
    private final int maxEntries;

    /**
     * 构造存储。
     *
     * @param ttlMillis TTL
     * @param maxEntries 容量上限
     */
    public MemoryIdempotencyStore(long ttlMillis, int maxEntries) {
        this.ttlMillis = Math.max(1000L, ttlMillis);
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public boolean firstSeen(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long expiresAt = seen.get(key);
        if (expiresAt != null && expiresAt > now) {
            return false;
        }
        if (expiresAt != null) {
            seen.remove(key);
        }
        if (seen.size() >= maxEntries) {
            evict(now);
        }
        boolean inserted = seen.putIfAbsent(key, now + ttlMillis) == null;
        if (inserted) {
            order.add(key);
        }
        return inserted;
    }

    private void evict(long now) {
        int target = Math.max(1, maxEntries / 10);
        for (int i = 0; i < target; i++) {
            String oldest = order.poll();
            if (oldest == null) {
                break;
            }
            Long expiresAt = seen.get(oldest);
            if (expiresAt == null || expiresAt <= now) {
                seen.remove(oldest);
            }
        }
        // 兜底：队列与实际 map 不同步时，直接淘汰任意条目
        if (seen.size() >= maxEntries) {
            Iterator<String> iterator = seen.keySet().iterator();
            if (iterator.hasNext()) {
                seen.remove(iterator.next());
            }
        }
    }
}
