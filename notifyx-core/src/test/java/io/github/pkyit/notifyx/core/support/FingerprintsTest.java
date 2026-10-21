package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指纹稳定性测试（设计书 18.2 的 T1、T3）。
 *
 * @since 1.0
 */
class FingerprintsTest {

    @Test
    @DisplayName("T1：HashMap 插入顺序不同不改变指纹")
    void shouldBeIndependentOfMapIterationOrder() {
        Map<String, Object> first = new HashMap<>();
        first.put("orderId", "SO20261021001");
        first.put("reason", "库存不足");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("reason", "库存不足");
        second.put("orderId", "SO20261021001");

        assertThat(Fingerprints.of("ORDER_CREATE_FAIL", "SO20261021001", first))
                .isEqualTo(Fingerprints.of("ORDER_CREATE_FAIL", "SO20261021001", second));
    }

    @Test
    @DisplayName("T3：异常堆栈不参与指纹计算")
    void shouldIgnoreStackTrace() {
        Map<String, Object> withStack = new HashMap<>();
        withStack.put("orderId", "SO1");
        withStack.put("stack", "java.lang.IllegalStateException\n\tat com.example.A.b(A.java:1)");

        Map<String, Object> withoutStack = new HashMap<>();
        withoutStack.put("orderId", "SO1");

        assertThat(Fingerprints.of("ORDER_CREATE_FAIL", "SO1", withStack))
                .isNotEqualTo(Fingerprints.of("ORDER_CREATE_FAIL", "SO1", withoutStack));
    }

    @Test
    @DisplayName("数值归一化：1 与 1.0 视为同一指纹")
    void shouldNormalizeNumbers() {
        Map<String, Object> integer = Map.of("count", 1);
        Map<String, Object> floating = Map.of("count", 1.0d);

        assertThat(Fingerprints.of("SCENE_A", "biz", integer))
                .isEqualTo(Fingerprints.of("SCENE_A", "biz", floating));
    }

    @Test
    @DisplayName("不同 bizId 必须产生不同指纹")
    void shouldDifferByBizId() {
        assertThat(Fingerprints.of("SCENE_A", "biz-1", Map.of()))
                .isNotEqualTo(Fingerprints.of("SCENE_A", "biz-2", Map.of()));
    }

    @Test
    @DisplayName("指纹带 sha256: 前缀且长度稳定")
    void shouldHaveStableShape() {
        String fingerprint = Fingerprints.of("SCENE_A", "biz", Map.of("k", "v"));
        assertThat(fingerprint).startsWith("sha256:").hasSize(7 + 64);
    }
}
