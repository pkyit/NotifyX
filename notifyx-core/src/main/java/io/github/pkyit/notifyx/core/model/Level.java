package io.github.pkyit.notifyx.core.model;

/**
 * 事件级别。CRITICAL 享有漏斗旁路特权，见设计书 9.2 的旁路矩阵。
 *
 * <p>@implNote 枚举天然线程安全且不可变。</p>
 *
 * @since 1.0
 */
public enum Level {

    /** 业务通知。 */
    INFO,

    /** 需要关注但业务仍可用，默认级别。 */
    WARN,

    /** 业务受损，需要人工介入。 */
    ERROR,

    /** 严重故障，需要立即响应；直通免打扰与限流。 */
    CRITICAL;

    /**
     * 判断当前级别是否不低于给定级别。
     *
     * @param other 对比级别，不可为 null
     * @return 当前级别优先级大于等于 other 时返回 true
     */
    public boolean atLeast(Level other) {
        return ordinal() >= other.ordinal();
    }
}
