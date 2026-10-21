package io.github.pkyit.notifyx.core.model;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次渠道投递的结果。
 *
 * <p>@implNote record 不可变，线程安全。</p>
 *
 * @param status 结果状态
 * @param messageId 渠道返回的消息 ID，可为 null
 * @param rawResponse 原始响应，已截断，可为 null
 * @param cost 耗时
 * @param failureReason 失败原因分类说明，成功时为 null
 * @since 1.0
 */
public record SendResult(Status status, String messageId, String rawResponse, Duration cost, String failureReason) {

    /** 原始响应最大保留长度。 */
    public static final int MAX_RAW_RESPONSE_LENGTH = 1024;

    /**
     * 结果状态。
     *
     * @since 1.0
     */
    public enum Status {

        /** 发送成功。 */
        SUCCESS,

        /** 可重试失败（超时、5xx、连接拒绝）。 */
        FAIL_RETRYABLE,

        /** 不可重试失败（认证失败、4xx 参数错、接收人不存在）。 */
        FAIL_NON_RETRYABLE,

        /** 被全局静默/场景禁用拦截。 */
        SUPPRESSED,

        /** 被去重拦截。 */
        DEDUPED,

        /** 被限流拦截。 */
        RATE_LIMITED,

        /** 渠道熔断中，未尝试发送。 */
        CIRCUIT_OPEN
    }

    /**
     * 规范化构造：截断原始响应，保证 cost 非空。
     *
     * @param status 状态
     * @param messageId 消息 ID
     * @param rawResponse 原始响应
     * @param cost 耗时
     * @param failureReason 失败原因
     */
    public SendResult {
        Objects.requireNonNull(status, "status");
        cost = cost == null ? Duration.ZERO : cost;
        rawResponse = truncate(rawResponse);
    }

    /**
     * 成功结果。
     *
     * @param messageId 消息 ID，可为 null
     * @param rawResponse 原始响应
     * @param cost 耗时
     * @return 成功结果
     */
    public static SendResult success(String messageId, String rawResponse, Duration cost) {
        return new SendResult(Status.SUCCESS, messageId, rawResponse, cost, null);
    }

    /**
     * 可重试失败。
     *
     * @param reason 失败原因
     * @param cost 耗时
     * @return 可重试失败结果
     */
    public static SendResult retryable(String reason, Duration cost) {
        return new SendResult(Status.FAIL_RETRYABLE, null, null, cost, reason);
    }

    /**
     * 不可重试失败。
     *
     * @param reason 失败原因
     * @param cost 耗时
     * @return 不可重试失败结果
     */
    public static SendResult nonRetryable(String reason, Duration cost) {
        return new SendResult(Status.FAIL_NON_RETRYABLE, null, null, cost, reason);
    }

    /**
     * 被抑制的结果。
     *
     * @param status 抑制类型，必须为 SUPPRESSED/DEDUPED/RATE_LIMITED/CIRCUIT_OPEN 之一
     * @param reason 原因说明
     * @return 抑制结果
     * @throws IllegalArgumentException status 不是抑制类型时抛出
     */
    public static SendResult suppressed(Status status, String reason) {
        if (status == Status.SUCCESS || status == Status.FAIL_RETRYABLE || status == Status.FAIL_NON_RETRYABLE) {
            throw new IllegalArgumentException("status must be a suppression status: " + status);
        }
        return new SendResult(status, null, null, Duration.ZERO, reason);
    }

    private static String truncate(String raw) {
        if (raw == null || raw.length() <= MAX_RAW_RESPONSE_LENGTH) {
            return raw;
        }
        return raw.substring(0, MAX_RAW_RESPONSE_LENGTH);
    }
}