package io.github.pkyit.notifyx.core.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;

/**
 * {@link NotifyEnvelope} 的 JSON 编解码（设计书 §6.5）。
 *
 * <p>用于失败落盘与 MQ 报文；{@code schemaVersion} 高于当前版本时拒绝解析，
 * 避免旧代码误读新语义。</p>
 *
 * <p>@implNote 无状态，线程安全；解析失败抛 {@link IllegalArgumentException}。</p>
 *
 * @since 1.0
 */
public final class NotifyEnvelopeCodec {

    private NotifyEnvelopeCodec() {
    }

    /**
     * 序列化报文。
     *
     * @param envelope 报文
     * @return JSON
     */
    public static String toJson(NotifyEnvelope envelope) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", envelope.schemaVersion());
        map.put("eventId", envelope.eventId());
        map.put("idempotencyKey", envelope.idempotencyKey());
        map.put("dedupeKey", envelope.dedupeKey());
        map.put("scene", envelope.scene());
        map.put("level", envelope.level().name());
        map.put("title", envelope.title());
        map.put("params", envelope.params());
        map.put("receivers", envelope.receivers());
        map.put("bizId", envelope.bizId());
        map.put("tags", envelope.tags());
        map.put("traceId", envelope.traceId());
        map.put("spanId", envelope.spanId());
        map.put("instanceId", envelope.instanceId());
        map.put("silentWindowSeconds", envelope.silentWindowSeconds());
        map.put("attempts", envelope.attempts());
        map.put("occurredAt", envelope.occurredAt());
        map.put("createdAt", envelope.createdAt());
        return JsonCodec.write(map);
    }

    /**
     * 解析报文。
     *
     * @param json JSON
     * @return 报文
     */
    public static NotifyEnvelope fromJson(String json) {
        Object parsed = JsonCodec.read(json);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("envelope json must be an object");
        }
        int schemaVersion = intValue(raw.get("schemaVersion"));
        if (schemaVersion > NotifyEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported envelope schemaVersion: " + schemaVersion);
        }
        return new NotifyEnvelope(schemaVersion,
                stringValue(raw.get("eventId")),
                stringValue(raw.get("idempotencyKey")),
                stringValue(raw.get("dedupeKey")),
                stringValue(raw.get("scene")),
                levelValue(raw.get("level")),
                stringValue(raw.get("title")),
                paramsValue(raw.get("params")),
                receiversValue(raw.get("receivers")),
                stringValue(raw.get("bizId")),
                tagsValue(raw.get("tags")),
                stringValue(raw.get("traceId")),
                stringValue(raw.get("spanId")),
                stringValue(raw.get("instanceId")),
                longValue(raw.get("silentWindowSeconds")),
                (int) longValue(raw.get("attempts")),
                longValue(raw.get("occurredAt")),
                longValue(raw.get("createdAt")));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        return (int) longValue(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Level levelValue(Object value) {
        if (value == null) {
            return Level.WARN;
        }
        return Level.valueOf(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        map.forEach((key, item) -> params.put(String.valueOf(key), item));
        return params;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tagsValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        map.forEach((key, item) -> tags.put(String.valueOf(key), item == null ? null : String.valueOf(item)));
        return tags;
    }

    private static List<String> receiversValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> receivers = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                receivers.add(String.valueOf(item));
            }
        }
        return receivers;
    }
}
