package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 零依赖 JSON 编解码测试（供 NotifyEnvelope / 落盘使用）。
 *
 * @since 1.0
 */
class JsonCodecTest {

    @Test
    @DisplayName("对象/数组/字符串/数字/布尔/null 往返")
    void shouldRoundTrip() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "订单\"失败\"\n第二行\tTab");
        value.put("count", 42L);
        value.put("ratio", 1.5d);
        value.put("ok", true);
        value.put("none", null);
        value.put("list", List.of("a", 1L, false));
        value.put("nested", Map.of("k", "v"));

        String json = JsonCodec.write(value);
        Object parsed = JsonCodec.read(json);

        assertThat(parsed).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) parsed;
        assertThat(map.get("text")).isEqualTo("订单\"失败\"\n第二行\tTab");
        assertThat(map.get("count")).isEqualTo(42L);
        assertThat(map.get("ratio")).isEqualTo(1.5d);
        assertThat(map.get("ok")).isEqualTo(true);
        assertThat(map.get("none")).isNull();
        List<Object> list = asList(map.get("list"));
        assertThat(list).containsExactly("a", 1L, false);
        Map<String, Object> nested = asMap(map.get("nested"));
        assertThat(nested).containsEntry("k", "v");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    @Test
    @DisplayName("Unicode 与转义字符按 JSON 规范输出与还原")
    void shouldEscape() {
        String json = JsonCodec.write("中文\"引号\"\\反斜杠\u0001");

        assertThat(json).startsWith("\"").endsWith("\"");
        assertThat(JsonCodec.read(json)).isEqualTo("中文\"引号\"\\反斜杠\u0001");
    }

    @Test
    @DisplayName("非法 JSON 抛 IllegalArgumentException")
    void shouldRejectMalformed() {
        assertThatThrownBy(() -> JsonCodec.read("{bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonCodec.read("{} trailing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonCodec.read(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NaN/Infinity 输出 null，避免产生非法 JSON")
    void shouldNotEmitInvalidNumbers() {
        assertThat(JsonCodec.write(Double.NaN)).isEqualTo("null");
        assertThat(JsonCodec.write(Double.POSITIVE_INFINITY)).isEqualTo("null");
    }
}
