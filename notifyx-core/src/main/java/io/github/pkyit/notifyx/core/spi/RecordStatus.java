package io.github.pkyit.notifyx.core.spi;

/**
 * 失败记录状态机（设计书 9.9）。
 *
 * <p>@implNote 枚举不可变，线程安全。</p>
 *
 * @since 1.0
 */
public enum RecordStatus {

    /** 待补偿。 */
    PENDING,

    /** 补偿中（乐观锁已抢占）。 */
    SENDING,

    /** 已发送成功。 */
    SENT,

    /** 死信，需人工介入。 */
    DEAD
}
