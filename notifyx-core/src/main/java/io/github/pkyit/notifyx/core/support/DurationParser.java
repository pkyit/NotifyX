package io.github.pkyit.notifyx.core.support;

import java.time.Duration;

/**
 * 简洁时长解析：{@code 30s} / {@code 10m} / {@code 2h} / {@code 1d}，纯数字按秒处理。
 *
 * <p>@implNote 无状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class DurationParser {

    private DurationParser() {
    }

    /**
     * 解析时长。
     *
     * @param text 文本，可为 null
     * @return 时长；文本为空时返回 {@link Duration#ZERO}
     * @throws IllegalArgumentException 格式非法时抛出
     */
    public static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            return Duration.ZERO;
        }
        String value = text.trim().toLowerCase();
        char unit = value.charAt(value.length() - 1);
        if (Character.isDigit(unit)) {
            return Duration.ofSeconds(Long.parseLong(value));
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("unsupported duration unit: " + unit);
        };
    }
}
