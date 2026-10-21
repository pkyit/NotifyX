package io.github.pkyit.notifyx.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于滑动窗口的单机限流器：同一限流键在窗口期内最多放行 {@code maxEvents} 次。
 *
 * <p>用于抑制“同一错误在短时间内刷屏”的情况。默认按 {@link AlertEvent#fingerprint()} 限流，
 * 因此不同标题的告警互不影响。</p>
 */
public final class SlidingWindowRateLimiter implements AlertRateLimiter {

    private final int maxEvents;
    private final long windowMillis;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int maxEvents, Duration window) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxEvents = maxEvents;
        this.windowMillis = window.toMillis();
    }

    @Override
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        boolean[] allowed = {false};
        windows.compute(key, (k, current) -> {
            Deque<Long> window = current == null ? new ArrayDeque<>() : current;
            while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
                window.pollFirst();
            }
            if (window.size() < maxEvents) {
                window.addLast(now);
                allowed[0] = true;
            }
            return window;
        });
        return allowed[0];
    }

    /**
     * 清理已过期的限流键，避免长期运行后 map 无限增长。
     *
     * <p>可以在业务侧定时调用（例如 {@code @Scheduled}），不调用也不影响正确性。</p>
     *
     * @return 被清理的键数量
     */
    public int evictExpired() {
        long now = System.currentTimeMillis();
        int before = windows.size();
        windows.entrySet().removeIf(entry -> {
            Deque<Long> window = entry.getValue();
            return window.isEmpty() || now - window.peekLast() >= windowMillis;
        });
        return before - windows.size();
    }

    /**
     * 当前被跟踪的限流键数量。
     */
    public int trackedKeys() {
        return windows.size();
    }
}
