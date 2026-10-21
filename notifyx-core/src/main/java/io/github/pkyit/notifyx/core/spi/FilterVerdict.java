package io.github.pkyit.notifyx.core.spi;

import java.time.Instant;

/**
 * 过滤器裁决结果。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @param decision 裁决
 * @param reason 原因，用于日志与指标标签
 * @param deferUntil DEFER 时的目标时间，其它裁决下可为 null
 * @since 1.0
 */
public record FilterVerdict(Decision decision, String reason, Instant deferUntil) {

    /**
     * 裁决类型。
     *
     * @since 1.0
     */
    public enum Decision {

        /** 放行，继续后续过滤器。 */
        PASS,

        /** 拒绝，终止管线。 */
        REJECT,

        /** 延后到指定时间重新投递。 */
        DEFER
    }

    /**
     * 放行。
     *
     * @return 裁决
     */
    public static FilterVerdict pass() {
        return new FilterVerdict(Decision.PASS, null, null);
    }

    /**
     * 拒绝。
     *
     * @param reason 原因
     * @return 裁决
     */
    public static FilterVerdict reject(String reason) {
        return new FilterVerdict(Decision.REJECT, reason, null);
    }

    /**
     * 延后。
     *
     * @param until 目标时间
     * @param reason 原因
     * @return 裁决
     */
    public static FilterVerdict deferUntil(Instant until, String reason) {
        return new FilterVerdict(Decision.DEFER, reason, until);
    }

    /**
     * 是否放行。
     *
     * @return 是否 PASS
     */
    public boolean isPass() {
        return decision == Decision.PASS;
    }
}
