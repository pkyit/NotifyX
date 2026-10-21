package io.github.pkyit.notifyx.core.model;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MQ / 落盘报文契约（设计书 §6.5）。
 *
 * <p>它是 {@link NotifyEvent} 的可序列化快照：带上运行时上下文（trace/instance/attempts）
 * 与幂等键，供 consumer 侧去重、失败补偿重投使用。</p>
 *
 * <p>@implNote 不可变；{@code schemaVersion} 是兼容性判定依据，新增字段不升版本、语义变更才升。</p>
 *
 * @param schemaVersion 报文版本
 * @param eventId 事件 ID
 * @param idempotencyKey 幂等键
 * @param dedupeKey 业务去重指纹
 * @param scene 场景码
 * @param level 级别
 * @param title 标题
 * @param params 模板参数
 * @param receivers 接收人
 * @param bizId 业务主键
 * @param tags 标签
 * @param traceId 链路 ID
 * @param spanId Span ID
 * @param instanceId 实例 ID
 * @param silentWindowSeconds 静默期秒数
 * @param attempts 已尝试次数
 * @param occurredAt 发生时间
 * @param createdAt 报文创建时间
 * @since 1.0
 */
public record NotifyEnvelope(
        int schemaVersion,
        String eventId,
        String idempotencyKey,
        String dedupeKey,
        String scene,
        Level level,
        String title,
        Map<String, Object> params,
        List<String> receivers,
        String bizId,
        Map<String, String> tags,
        String traceId,
        String spanId,
        String instanceId,
        long silentWindowSeconds,
        int attempts,
        long occurredAt,
        long createdAt) {

    /** 当前报文版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 规范化。
     *
     * @param schemaVersion 版本
     * @param eventId 事件 ID
     * @param idempotencyKey 幂等键
     * @param dedupeKey 去重指纹
     * @param scene 场景
     * @param level 级别
     * @param title 标题
     * @param params 参数
     * @param receivers 接收人
     * @param bizId 业务主键
     * @param tags 标签
     * @param traceId 链路 ID
     * @param spanId Span ID
     * @param instanceId 实例
     * @param silentWindowSeconds 静默秒数
     * @param attempts 尝试次数
     * @param occurredAt 发生时间
     * @param createdAt 创建时间
     */
    public NotifyEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(scene, "scene");
        level = level == null ? Level.WARN : level;
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        receivers = receivers == null ? List.of() : List.copyOf(receivers);
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        if (schemaVersion <= 0) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
    }

    /**
     * 从事件与运行时上下文组装报文。
     *
     * @param event 事件
     * @param dedupeKey 已计算的去重指纹
     * @param traceId 链路 ID，可为 null
     * @param spanId Span ID，可为 null
     * @param instanceId 实例 ID
     * @param attempts 已尝试次数
     * @param createdAt 创建时间
     * @return 报文
     */
    public static NotifyEnvelope of(NotifyEvent event, String dedupeKey, String traceId, String spanId,
                                    String instanceId, int attempts, long createdAt) {
        Objects.requireNonNull(event, "event");
        String idempotencyKey = dedupeKey == null || dedupeKey.isBlank() ? event.eventId() : dedupeKey;
        long silentSeconds = event.silentWindow() == null ? 0L : event.silentWindow().toSeconds();
        return new NotifyEnvelope(CURRENT_SCHEMA_VERSION, event.eventId(), idempotencyKey, dedupeKey,
                event.scene(), event.level(), event.title(), event.params(), List.copyOf(event.receivers()),
                event.bizId(), event.tags(), traceId, spanId, instanceId, silentSeconds, attempts,
                event.occurredAt(), createdAt);
    }

    /**
     * 还原为可投递事件（失败补偿用）。
     *
     * @return 事件
     */
    public NotifyEvent toEvent() {
        NotifyEvent.Builder builder = NotifyEvent.builder(scene)
                .eventId(eventId)
                .level(level)
                .params(params)
                .receivers(receivers)
                .bizId(bizId)
                .dedupeKey(dedupeKey)
                .occurredAt(occurredAt)
                .async(false);
        if (title != null) {
            builder.title(title);
        }
        if (silentWindowSeconds > 0) {
            builder.silentWindow(Duration.ofSeconds(silentWindowSeconds));
        }
        tags.forEach(builder::tag);
        if (traceId != null) {
            builder.tag("traceId", traceId);
        }
        if (spanId != null) {
            builder.tag("spanId", spanId);
        }
        return builder.build();
    }
}
