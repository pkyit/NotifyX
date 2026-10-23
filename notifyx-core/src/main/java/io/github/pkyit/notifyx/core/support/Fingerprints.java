package io.github.pkyit.notifyx.core.support;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 去重指纹算法（设计书 9.1）。
 *
 * <pre>
 * dedupeKey = sha256( scene U+0001 bizId U+0001 md5( sorted(params) ) )
 * </pre>
 *
 * <p>算法必须稳定可复现：跨实例、跨进程、跨重启算出来一致。</p>
 *
 * <p>@implNote 无状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class Fingerprints {

    private static final String SEPARATOR = "\u0001";
    private static final String PAIR_SEPARATOR = "&";
    private static final String NULL_LITERAL = "__NULL__";

    private Fingerprints() {
    }

    /**
     * 计算业务指纹。bizId 为空时退化为按渲染内容去重（见 {@link #ofContent}）。
     *
     * @param scene 场景码，不可为 null
     * @param bizId 业务主键，可为 null
     * @param params 模板变量，可为 null
     * @return 形如 {@code sha256:xxxx} 的指纹
     */
    public static String of(String scene, String bizId, Map<String, Object> params) {
        String paramsDigest = md5Hex(canonicalParams(params));
        String payload = scene + SEPARATOR + nullSafe(bizId) + SEPARATOR + paramsDigest;
        return "sha256:" + sha256Hex(payload);
    }

    /**
     * 按渲染后内容计算指纹，用于 bizId 为空的退化路径。
     *
     * @param scene 场景码
     * @param renderedContent 已渲染内容
     * @return 指纹
     */
    public static String ofContent(String scene, String renderedContent) {
        return "sha256:" + sha256Hex(scene + SEPARATOR + md5Hex(nullSafe(renderedContent)));
    }

    /**
     * 规范化 params：key 字典序、嵌套递归排序、List 保序、数值归一、时间转 epoch millis。
     *
     * @param params 原始变量
     * @return 规范化字符串，可直接参与摘要计算
     */
    public static String canonicalParams(Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendMap(builder, params);
        return builder.toString();
    }

    private static void appendMap(StringBuilder builder, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(PAIR_SEPARATOR);
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(canonicalValue(entry.getValue()));
        }
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return NULL_LITERAL;
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            long asLong = number.longValue();
            return asDouble == (double) asLong ? Long.toString(asLong) : Double.toString(asDouble);
        }
        if (value instanceof Boolean || value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Instant instant) {
            return Long.toString(instant.toEpochMilli());
        }
        if (value instanceof java.util.Date date) {
            return Long.toString(date.getTime());
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value.getClass().isArray()) {
            // 与 Collection 使用同一规范化格式，避免数组退化为 identity hash。
            int length = Array.getLength(value);
            List<String> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                items.add(canonicalValue(Array.get(value, i)));
            }
            return "[" + String.join(",", items) + "]";
        }
        if (value instanceof Map<?, ?> nested) {
            StringBuilder builder = new StringBuilder("{");
            appendMap(builder, nested);
            return builder.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>(collection.size());
            for (Object item : collection) {
                items.add(canonicalValue(item));
            }
            return "[" + String.join(",", items) + "]";
        }
        return value.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? NULL_LITERAL : value;
    }

    private static String md5Hex(String value) {
        return digestHex("MD5", value);
    }

    private static String sha256Hex(String value) {
        return digestHex("SHA-256", value);
    }

    private static String digestHex(String algorithm, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " not available", ex);
        }
    }
}
