package io.github.pkyit.notifyx.core.support;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/**
 * 最小 JSON 编解码（设计书 6.4：core 零依赖，不引 Jackson）。
 *
 * <p>支持对象 / 数组 / 字符串 / 数字 / 布尔 / null；对象键按写入顺序输出。
 * 数字：整数读成 {@link Long}，带小数点或指数的读成 {@link Double}。</p>
 *
 * <p>@implNote 无状态，线程安全；解析失败抛 {@link IllegalArgumentException}。</p>
 *
 * @since 1.0
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    /**
     * 序列化为 JSON。
     *
     * @param value 值
     * @return JSON 文本
     */
    public static String write(Object value) {
        StringBuilder builder = new StringBuilder(256);
        writeValue(value, builder);
        return builder.toString();
    }

    /**
     * 解析 JSON。
     *
     * @param text JSON 文本
     * @return 解析结果（Map/List/String/Long/Double/Boolean/null）
     */
    public static Object read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("json text is null");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("unexpected trailing content at " + parser.index);
        }
        return value;
    }

    private static void writeValue(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(text, builder);
        } else if (value instanceof Boolean bool) {
            builder.append(bool);
        } else if (value instanceof Number number) {
            writeNumber(number, builder);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, builder);
        } else if (value instanceof Collection<?> collection) {
            writeArray(collection, builder);
        } else if (value.getClass().isArray()) {
            builder.append('[');
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeValue(Array.get(value, i), builder);
            }
            builder.append(']');
        } else if (value instanceof Character character) {
            writeString(String.valueOf(character), builder);
        } else {
            writeString(String.valueOf(value), builder);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder builder) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeString(String.valueOf(entry.getKey()), builder);
            builder.append(':');
            writeValue(entry.getValue(), builder);
        }
        builder.append('}');
    }

    private static void writeArray(Collection<?> collection, StringBuilder builder) {
        builder.append('[');
        boolean first = true;
        for (Object item : collection) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeValue(item, builder);
        }
        builder.append(']');
    }

    private static void writeNumber(Number number, StringBuilder builder) {
        if (number instanceof Double d && (d.isNaN() || d.isInfinite())
                || number instanceof Float f && (f.isNaN() || f.isInfinite())) {
            builder.append("null");
            return;
        }
        builder.append(number);
    }

    private static void writeString(String value, StringBuilder builder) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {

        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object parseValue() {
            skipWhitespace();
            if (eof()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            char ch = text.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char ch = next();
                if (ch == '}') {
                    return map;
                }
                if (ch != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + (index - 1));
                }
            }
        }

        private java.util.List<Object> parseArray() {
            expect('[');
            java.util.List<Object> list = new java.util.ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char ch = next();
                if (ch == ']') {
                    return list;
                }
                if (ch != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + (index - 1));
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch != '\\') {
                    builder.append(ch);
                    continue;
                }
                char escaped = next();
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    default -> throw new IllegalArgumentException("bad escape \\" + escaped);
                }
                if (escaped == 'u') {
                    index += 4;
                }
            }
        }

        private Object parseNumber() {
            int start = index;
            while (!eof() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("unexpected character at " + start);
            }
            try {
                if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("bad number '" + raw + "'", ex);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("bad literal at " + index);
            }
            index += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            char actual = next();
            if (actual != expected) {
                throw new IllegalArgumentException("expected '" + expected + "' but was '" + actual + "'");
            }
        }

        private char peek() {
            if (eof()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            return text.charAt(index);
        }

        private char next() {
            if (eof()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            return text.charAt(index++);
        }

        private void skipWhitespace() {
            while (!eof() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean eof() {
            return index >= text.length();
        }
    }
}
