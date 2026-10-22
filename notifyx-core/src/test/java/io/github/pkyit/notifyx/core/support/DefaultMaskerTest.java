package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 脱敏测试（设计书 16.2）：手机/邮箱/身份证/银行卡与文本兜底。
 *
 * @since 1.0
 */
class DefaultMaskerTest {

    private final DefaultMasker masker = new DefaultMasker();

    @Test
    @DisplayName("手机号保留前 3 后 4")
    void shouldMaskPhone() {
        assertThat(masker.maskPhone("13812345678")).isEqualTo("138****5678");
        assertThat(masker.maskPhone("1234567")).isEqualTo("123****4567");
        assertThat(masker.maskPhone("123")).isEqualTo("****");
        assertThat(masker.maskPhone(null)).isNull();
    }

    @Test
    @DisplayName("邮箱保留首字母与域名")
    void shouldMaskEmail() {
        assertThat(masker.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(masker.maskEmail("no-at-sign")).isEqualTo("****");
        assertThat(masker.maskEmail("@example.com")).isEqualTo("****");
        assertThat(masker.maskEmail(null)).isNull();
    }

    @Test
    @DisplayName("身份证与银行卡保留首尾")
    void shouldMaskIdCardAndBankCard() {
        assertThat(masker.maskIdCard("110101199001011234")).startsWith("110").contains("****").endsWith("1234");
        assertThat(masker.maskIdCard("1234567")).isEqualTo("****");
        assertThat(masker.maskIdCard(null)).isNull();

        assertThat(masker.maskBankCard("6222020000001234")).startsWith("6222").endsWith("1234");
        assertThat(masker.maskBankCard("12345678")).isEqualTo("****");
        assertThat(masker.maskBankCard(null)).isNull();
    }

    @Test
    @DisplayName("maskParams 按 key 名递归匹配，非字符串原样返回")
    void shouldMaskParamsByKey() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("phone", "13812345678");
        params.put("mobile", "13912345678");
        params.put("email", "bob@example.com");
        params.put("idCard", "110101199001011234");
        params.put("bankCard", "6222020000001234");
        params.put("orderId", "SO-1");
        params.put("count", 42);

        Map<String, Object> masked = masker.maskParams(params);

        assertThat(masked.get("phone")).isEqualTo("138****5678");
        assertThat(masked.get("mobile")).isEqualTo("139****5678");
        assertThat(masked.get("email")).isEqualTo("b***@example.com");
        assertThat(masked.get("idCard")).asString().endsWith("1234");
        assertThat(masked.get("bankCard")).asString().endsWith("1234");        assertThat(masked.get("orderId")).isEqualTo("SO-1");
        assertThat(masked.get("count")).isEqualTo(42);
        assertThat(masker.maskParams(null)).isEmpty();
        assertThat(masker.maskParams(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("maskParams 对嵌套 Map / List 递归脱敏")
    @SuppressWarnings("unchecked")
    void shouldMaskNestedParams() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("phone", "13812345678");
        profile.put("email", "bob@example.com");
        profile.put("orderId", "SO-2");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", profile);
        params.put("contacts", java.util.List.of(
                Map.of("mobile", "13912345678"),
                Map.of("note", "vip")));

        Map<String, Object> masked = masker.maskParams(params);

        Map<String, Object> maskedProfile = (Map<String, Object>) masked.get("user");
        assertThat(maskedProfile).containsEntry("phone", "138****5678");
        assertThat(maskedProfile).containsEntry("email", "b***@example.com");
        assertThat(maskedProfile).containsEntry("orderId", "SO-2");

        java.util.List<Map<String, Object>> contacts = (java.util.List<Map<String, Object>>) masked.get("contacts");
        assertThat(contacts.get(0)).containsEntry("mobile", "139****5678");
        assertThat(contacts.get(1)).containsEntry("note", "vip");
        assertThat(profile.get("phone")).isEqualTo("13812345678");
    }

    @Test
    @DisplayName("maskText 兜底脱敏文本中的身份证号")
    void shouldMaskTextIdCards() {
        String text = "用户身份证 110101199001011234 校验失败";
        String masked = masker.maskText(text);

        assertThat(masked).contains("110101***********1234");
        assertThat(masked).doesNotContain("199001011234");
        assertThat(masker.maskText(null)).isNull();
        assertThat(masker.maskText("")).isEmpty();
        assertThat(masker.maskText("无敏感信息")).isEqualTo("无敏感信息");
    }

    @Test
    @DisplayName("辅助工具类 DurationParser 支持 s/m/h/d 与纯数字秒")
    void shouldParseDurations() {
        assertThat(DurationParser.parse("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(DurationParser.parse("10m")).isEqualTo(Duration.ofMinutes(10));
        assertThat(DurationParser.parse("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(DurationParser.parse("1d")).isEqualTo(Duration.ofDays(1));
        assertThat(DurationParser.parse("45")).isEqualTo(Duration.ofSeconds(45));
        assertThat(DurationParser.parse(" 5M ")).isEqualTo(Duration.ofMinutes(5));
        assertThat(DurationParser.parse(null)).isEqualTo(Duration.ZERO);
        assertThat(DurationParser.parse(" ")).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("DurationParser 对非法单位抛 IllegalArgumentException")
    void shouldRejectUnsupportedUnit() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DurationParser.parse("10x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported duration unit");
    }
}
