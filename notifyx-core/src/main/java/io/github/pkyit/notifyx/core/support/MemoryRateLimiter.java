package io.github.pkyit.notifyx.core.support;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * 内存滑动窗口限流器（T0 梯度默认实现）。
 *
 * <p>每个 key 维护一个时间戳双端队列，窗口外的元素在每次访问时清理。
 * 窗口内放行次数达到阈值后拒绝。</p>
 *
 * <p>@implNote 线程安全；{@code tryAcquire} 通过 {@link ConcurrentHashMap#compute} 原子化。</p>
 *
 * @since 1.0
 */
public final class MemoryRateLimiter implements RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int permits, Duration window) {
        if (permits <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, window.toMillis());
        boolean[] acquired = {false};
        windows.compute(key, (k, current) -> {
            Deque<Long> deque = current == null ? new ArrayDeque<>() : current;
            while (!deque.isEmpty() && now - deque.peekFirst() >= windowMillis) {
                deque.pollFirst();
            }
            if (deque.size() < permits) {
                deque.addLast(now);
                acquired[0] = true;
            }
            return deque;
        });
        return acquired[0];
    }

    @Override
    public long currentCount(String key, Duration window) {
        Deque<Long> deque = windows.get(key);
        return deque == null ? 0L : deque.size();
    }

    /**
     * 清理已完全过期的 key，避免长期运行后 map 无限增长。
     *
     * @param window 窗口
     * @return 清理数量
     */
    public int evictExpired(Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, window.toMillis());
        int before = windows.size();
        windows.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            return deque.isEmpty() || now - deque.peekLast() >= windowMillis;
        });
        return before - windows.size();
    }
}
