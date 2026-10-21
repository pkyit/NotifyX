package io.github.pkyit.notifyx.core.spi;

import java.util.Objects;

/**
 * 失败落盘记录。
 *
 * <p>@implNote 不可变 record，线程安全。</p>
 *
 * @param recordId 记录 ID，取自 eventId
 * @param status 状态
 * @param version 乐观锁版本
 * @param envelopeJson 完整报文（JSON）
 * @param channel 渠道名
 * @param failureReason 失败原因
 * @param attempts 已尝试次数
 * @param createdAt 创建时间
 * @param nextRetryAt 下次重试时间
 * @since 1.0
 */
public record FailedRecord(
        String recordId,
        RecordStatus status,
        int version,
        String envelopeJson,
        String channel,
        String failureReason,
        int attempts,
        long createdAt,
        long nextRetryAt) {

    /**
     * 规范化构造。
     *
     * @param recordId 记录 ID
     * @param status 状态
     * @param version 版本
     * @param envelopeJson 报文
     * @param channel 渠道
     * @param failureReason 原因
     * @param attempts 尝试次数
     * @param createdAt 创建时间
     * @param nextRetryAt 下次重试时间
     */
    public FailedRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(envelopeJson, "envelopeJson");
    }

    /**
     * 返回一个进入下一次重试的记录。
     *
     * @param nextAttempts 新的尝试次数
     * @param nextRetryAt 下次重试时间
     * @return 新记录
     */
    public FailedRecord nextAttempt(int nextAttempts, long nextRetryAt) {
        return new FailedRecord(recordId, RecordStatus.PENDING, version + 1, envelopeJson, channel,
                failureReason, nextAttempts, createdAt, nextRetryAt);
    }
}
