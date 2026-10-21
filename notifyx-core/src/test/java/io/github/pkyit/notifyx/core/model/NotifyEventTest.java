package io.github.pkyit.notifyx.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件模型约束测试（设计书 6.1）。
 *
 * @since 1.0
 */
class NotifyEventTest {

    @Test
    @DisplayName("非法场景码必须抛出 IllegalArgumentException")
    void shouldRejectInvalidScene() {
        assertThatThrownBy(() -> NotifyEvent.builder("order-create").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scene");
    }

    @Test
    @DisplayName("params 防御性拷贝：外部修改不影响事件")
    void shouldDefensivelyCopyParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("k", "v");
        NotifyEvent event = NotifyEvent.builder("SCENE_A").params(params).build();
        params.put("k", "changed");

        assertThat(event.params()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("接收人去空白去重")
    void shouldNormalizeReceivers() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .receivers(java.util.List.of(" a@x.com ", "a@x.com", "b@x.com"))
                .build();

        assertThat(event.receivers()).containsExactly("a@x.com", "b@x.com");
    }

    @Test
    @DisplayName("默认级别为 WARN，eventId 以 evt_ 开头")
    void shouldApplyDefaults() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A").build();
        assertThat(event.level()).isEqualTo(Level.WARN);
        assertThat(event.eventId()).startsWith("evt_");
    }
}