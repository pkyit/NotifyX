package io.github.pkyit.notifyx.core.spi;

import java.util.Objects;

/**
 * 发送审计记录（接收人应已脱敏）。
 *
 * <p>@implNote 不可变 record，线程安全。</p>
 *
 * @param eventId 事件 ID
 * @param scene 场景码
 * @param level 级别
 * @param bizId 业务主键
 * @param channel 渠道
 * @param receiver 接收人（脱敏）
 * @param status 状态
 * @param errorMsg 错误信息
 * @param attempts 尝试次数
 * @param costMs 耗时（毫秒）
 * @param instanceId 实例 ID
 * @param traceId 链路 ID
 * @param createdAt 创建时间
 * @param sentAt 发送成功时间
 * @since 1.0
 */
public record AuditRecord(
        String eventId,
        String scene,
        String level,
        String bizId,
        String channel,
        String receiver,
        String status,
        String errorMsg,
        int attempts,
        long costMs,
        String instanceId,
        String traceId,
        long createdAt,
        Long sentAt) {

    /**
     * 规范化构造。
     *
     * @param eventId 事件 ID
     * @param scene 场景码
     * @param level 级别
     * @param bizId 业务主键
     * @param channel 渠道
     * @param receiver 接收人
     * @param status 状态
     * @param errorMsg 错误信息
     * @param attempts 尝试次数
     * @param costMs 耗时
     * @param instanceId 实例 ID
     * @param traceId 链路 ID
     * @param createdAt 创建时间
     * @param sentAt 发送时间
     */
    public AuditRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(status, "status");
    }
}
