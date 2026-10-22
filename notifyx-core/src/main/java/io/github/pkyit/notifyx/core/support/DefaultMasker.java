package io.github.pkyit.notifyx.core.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.pkyit.notifyx.core.spi.Masker;

/**
 * 默认脱敏实现。
 *
 * <p>原则：保留前后若干位以便人工辨认，中间一律替换为 {@code ****}。</p>
 *
 * <p>@implNote 无状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class DefaultMasker implements Masker {

    private static final String MASK = "****";
    private static final int MAX_DEPTH = 10;
    private static final Set<String> PHONE_KEYS = Set.of("phone", "mobile", "tel", "receiver");
    private static final Set<String> EMAIL_KEYS = Set.of("email", "mail");
    private static final Set<String> ID_CARD_KEYS = Set.of("idcard", "idno", "certno");
    private static final Set<String> BANK_CARD_KEYS = Set.of("bankcard", "cardno", "account");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");

    @Override
    public String maskPhone(String value) {
        if (value == null || value.length() < 7) {
            return value == null ? null : MASK;
        }
        return value.substring(0, 3) + MASK + value.substring(value.length() - 4);
    }

    @Override
    public String maskEmail(String value) {
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    @Override
    public String maskIdCard(String value) {
        if (value == null || value.length() < 8) {
            return value == null ? null : MASK;
        }
        return value.substring(0, 3) + "***********" + value.substring(value.length() - 4);
    }

    @Override
    public String maskBankCard(String value) {
        if (value == null || value.length() < 9) {
            return value == null ? null : MASK;
        }
        return value.substring(0, 4) + "***********" + value.substring(value.length() - 4);
    }

    @Override
    public String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return ID_CARD_PATTERN.matcher(text).replaceAll("$1***********$2");
    }

    @Override
    public Map<String, Object> maskParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        return maskMap(params, 0);
    }

    private Map<String, Object> maskMap(Map<?, ?> params, int depth) {
        Map<String, Object> masked = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            String name = key == null ? null : String.valueOf(key);
            masked.put(name, maskValue(name, value, depth + 1));
        });
        return masked;
    }

    private Object maskValue(String key, Object value, int depth) {
        if (value instanceof Map<?, ?> nested) {
            return depth >= MAX_DEPTH ? MASK : maskMap(nested, depth);
        }
        if (value instanceof Iterable<?> iterable) {
            if (depth >= MAX_DEPTH) {
                return MASK;
            }
            List<Object> masked = new ArrayList<>();
            iterable.forEach(item -> masked.add(maskValue(key, item, depth + 1)));
            return masked;
        }
        if (!(value instanceof String text) || key == null) {
            return value;
        }
        String normalized = key.toLowerCase();
        if (PHONE_KEYS.contains(normalized)) {
            return maskPhone(text);
        }
        if (EMAIL_KEYS.contains(normalized)) {
            return maskEmail(text);
        }
        if (ID_CARD_KEYS.contains(normalized)) {
            return maskIdCard(text);
        }
        if (BANK_CARD_KEYS.contains(normalized)) {
            return maskBankCard(text);
        }
        return value;
    }
}
