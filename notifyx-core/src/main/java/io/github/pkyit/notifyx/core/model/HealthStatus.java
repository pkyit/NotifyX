package io.github.pkyit.notifyx.core.model;

/**
 * 渠道健康状态。
 *
 * <p>@implNote 枚举天然线程安全且不可变。</p>
 *
 * @since 1.0
 */
public enum HealthStatus {

    /** 健康。 */
    UP,

    /** 降级：部分能力不可用，但可继续发送。 */
    DEGRADED,

    /** 不可用。 */
    DOWN,

    /** 未探测。默认值，避免探活产生真实流量。 */
    UNKNOWN
}
