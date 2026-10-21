package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单计数熔断器（设计书 9.7）。按渠道实例持有。
 *
 * <p>连续失败达到阈值后打开；打开期间请求被拒绝；到期后进入半开，放一条试探，
 * 成功则关闭，失败则重新打开且退避翻倍（上限 {@code maxOpenDuration}）。</p>
 *
 * <p>@implNote 线程安全；状态迁移在对象锁内完成。</p>
 *
 * @since 1.0
 */
public final class CircuitBreaker {

    /**
     * 熔断状态。
     *
     * @since 1.0
     */
    public enum State {
        /** 关闭，正常放行。 */
        CLOSED,
        /** 打开，拒绝请求。 */
        OPEN,
        /** 半开，放行一条试探。 */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Duration maxOpenDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile State state = State.CLOSED;
    private volatile long openUntil;
    private volatile Duration currentOpenDuration;
    private volatile boolean probeInFlight;

    /**
     * 构造熔断器。
     *
     * @param failureThreshold 连续失败阈值
     * @param openDuration 初始打开时长
     * @param maxOpenDuration 打开时长上限
     */
    public CircuitBreaker(int failureThreshold, Duration openDuration, Duration maxOpenDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        this.maxOpenDuration = maxOpenDuration == null ? Duration.ofMinutes(5) : maxOpenDuration;
        this.currentOpenDuration = this.openDuration;
    }

    /**
     * 是否允许本次请求通过。
     *
     * @return true=允许
     */
    public synchronized boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (state == State.OPEN) {
            if (now < openUntil) {
                return false;
            }
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        if (state == State.HALF_OPEN) {
            if (probeInFlight) {
                return false;
            }
            probeInFlight = true;
        }
        return true;
    }

    /**
     * 记录一次成功。
     */
    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        currentOpenDuration = openDuration;
        state = State.CLOSED;
        probeInFlight = false;
    }

    /**
     * 记录一次失败。
     */
    public synchronized void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        if (failures >= failureThreshold) {
            open();
        }
    }

    /**
     * 当前状态。
     *
     * @return 状态
     */
    public State state() {
        return state;
    }

    /**
     * 熔断剩余毫秒数，未打开时为 0。
     *
     * @return 剩余毫秒
     */
    public long remainingOpenMillis() {
        return state == State.OPEN ? Math.max(0L, openUntil - System.currentTimeMillis()) : 0L;
    }

    private void open() {
        state = State.OPEN;
        probeInFlight = false;
        openUntil = System.currentTimeMillis() + currentOpenDuration.toMillis();
        long nextMillis = Math.min(currentOpenDuration.toMillis() * 2, maxOpenDuration.toMillis());
        currentOpenDuration = Duration.ofMillis(nextMillis);
    }
}