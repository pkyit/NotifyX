package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * NotifyEnvelope 编解码测试（设计书 §6.5）：字段往返、版本守卫、还原为事件。
 *
 * @since 1.0
 */
class NotifyEnvelopeCodecTest {

    @Test
    @DisplayName("报文 JSON 往返保留全部字段（含嵌套参数与中文）")
    void shouldRoundTripEnvelope() {
        NotifyEvent event = NotifyEvent.builder("ORDER_CREATE_FAIL")
                .level(Level.ERROR)
                .title("【订单异常】SO1")
                .bizId("SO1")
                .param("orderId", "SO1")
                .param("reason", "库存\"不足\"\n第二行")
                .param("nested", Map.of("k", "v"))
                .receiver("ops@example.com")
                .tag("env", "prod")
                .silentWindow(Duration.ofMinutes(5))
                .occurredAt(1729500000000L)
                .build();
        NotifyEnvelope envelope = NotifyEnvelope.of(event, "sha256:abc", "trace-1", "span-1", "inst-1", 2, 1729500000123L);

        String json = NotifyEnvelopeCodec.toJson(envelope);
        NotifyEnvelope decoded = NotifyEnvelopeCodec.fromJson(json);

        assertThat(decoded.schemaVersion()).isEqualTo(NotifyEnvelope.CURRENT_SCHEMA_VERSION);
        assertThat(decoded.eventId()).isEqualTo(event.eventId());
        assertThat(decoded.idempotencyKey()).isEqualTo("sha256:abc");
        assertThat(decoded.dedupeKey()).isEqualTo("sha256:abc");
        assertThat(decoded.scene()).isEqualTo("ORDER_CREATE_FAIL");
        assertThat(decoded.level()).isEqualTo(Level.ERROR);
        assertThat(decoded.title()).isEqualTo("【订单异常】SO1");
        assertThat(decoded.params()).containsEntry("reason", "库存\"不足\"\n第二行");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) decoded.params().get("nested");
        assertThat(nested).containsEntry("k", "v");
        assertThat(decoded.receivers()).containsExactly("ops@example.com");
        assertThat(decoded.bizId()).isEqualTo("SO1");
        assertThat(decoded.tags()).containsEntry("env", "prod");
        assertThat(decoded.traceId()).isEqualTo("trace-1");
        assertThat(decoded.spanId()).isEqualTo("span-1");
        assertThat(decoded.instanceId()).isEqualTo("inst-1");
        assertThat(decoded.silentWindowSeconds()).isEqualTo(300);
        assertThat(decoded.attempts()).isEqualTo(2);
        assertThat(decoded.occurredAt()).isEqualTo(1729500000000L);
        assertThat(decoded.createdAt()).isEqualTo(1729500000123L);
    }

    @Test
    @DisplayName("报文可还原为可投递事件，且强制同步执行")
    void shouldConvertBackToEvent() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .level(Level.WARN)
                .bizId("B1")
                .param("k", "v")
                .receiver("a@x.com")
                .silentWindow(Duration.ofMinutes(1))
                .build();
        NotifyEnvelope envelope = NotifyEnvelope.of(event, "dedupe-1", "trace", "span", "inst", 1, 1L);

        NotifyEvent restored = NotifyEnvelopeCodec.fromJson(NotifyEnvelopeCodec.toJson(envelope)).toEvent();

        assertThat(restored.scene()).isEqualTo("SCENE_A");
        assertThat(restored.level()).isEqualTo(Level.WARN);
        assertThat(restored.bizId()).isEqualTo("B1");
        assertThat(restored.params()).containsEntry("k", "v");
        assertThat(restored.receivers()).containsExactly("a@x.com");
        assertThat(restored.silentWindow()).hasMinutes(1);
        assertThat(restored.async()).isFalse();
        assertThat(restored.tag("traceId")).isEqualTo("trace");
        assertThat(restored.tag("spanId")).isEqualTo("span");
    }

    @Test
    @DisplayName("schemaVersion 高于当前版本时拒绝解析")
    void shouldRejectFutureSchema() {
        String json = NotifyEnvelopeCodec.toJson(NotifyEnvelope.of(
                NotifyEvent.builder("SCENE_A").build(), "k", null, null, "i", 0, 1L))
                .replace("\"schemaVersion\":1", "\"schemaVersion\":99");

        assertThatThrownBy(() -> NotifyEnvelopeCodec.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @DisplayName("非对象 / 非法 JSON 抛 IllegalArgumentException")
    void shouldRejectMalformed() {
        assertThatThrownBy(() -> NotifyEnvelopeCodec.fromJson("[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");
        assertThatThrownBy(() -> NotifyEnvelopeCodec.fromJson("{bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
