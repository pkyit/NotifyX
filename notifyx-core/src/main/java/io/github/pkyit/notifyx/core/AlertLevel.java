package io.github.pkyit.notifyx.core;

/**
 * 告警级别。
 *
 * <p>级别只表达业务语义与相对优先级，具体投递策略（哪些级别下发给哪些通道）
 * 由 {@link AlertChannel#supports(AlertEvent)} 与 {@code AlertDispatcher} 的过滤条件决定，
 * 核心层不预设“级别 → 通道”的耦合关系。</p>
 */
public enum AlertLevel {

    /** 普通信息，一般不投递，仅审计留痕。 */
    INFO(10),

    /** 需要关注，但业务仍可用。 */
    WARN(20),

    /** 业务受损，需要人工介入。 */
    ERROR(30),

    /** 严重故障，需要立即响应。 */
    CRITICAL(40);

    private final int priority;

    AlertLevel(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /**
     * 当前级别是否不低于给定级别。
     */
    public boolean atLeast(AlertLevel other) {
        return this.priority >= other.priority;
    }
}
