package io.github.pkyit.notifyx.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
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

    @Test
    @DisplayName("params 超过 64KB 时拒绝构建，防止把大对象带进通知")
    void shouldRejectOversizedParams() {
        assertThatThrownBy(() -> NotifyEvent.builder("SCENE_A")
                .param("big", "x".repeat(40_000))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params exceed");
    }

    @Test
    @DisplayName("null 参数值归一为 __NULL__，Instant 归一为 epoch millis")
    void shouldNormalizeValues() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .param("none", null)
                .param("at", java.time.Instant.ofEpochMilli(1729500000000L))
                .build();

        assertThat(event.params()).containsEntry("none", "__NULL__");
        assertThat(event.params()).containsEntry("at", 1729500000000L);
    }

    @Test
    @DisplayName("嵌套超过 3 层的参数退化为字符串，避免深拷贝开销")
    void shouldFlattenDeepNesting() {
        Map<String, Object> level3 = Map.of("d", "v");
        Map<String, Object> level2 = Map.of("c", level3);
        Map<String, Object> level1 = Map.of("b", level2);

        NotifyEvent event = NotifyEvent.builder("SCENE_A").param("a", level1).build();

        assertThat(String.valueOf(event.params().get("a"))).contains("d=v");
    }

    @Test
    @DisplayName("数组参数归一为不可变 List，避免 identity hash 进入指纹")
    void shouldNormalizeArrayParams() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .param("args", new Object[]{"SO1", 2})
                .param("ids", new int[]{7, 8})
                .build();

        assertThat(event.params().get("args")).isEqualTo(List.of("SO1", 2));
        assertThat(event.params().get("ids")).isEqualTo(List.of(7, 8));
    }

    @Test
    @DisplayName("tags 防御性拷贝且可按 key 读取")
    void shouldCopyTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("traceId", "t-1");
        NotifyEvent event = NotifyEvent.builder("SCENE_A").tags(tags).build();
        tags.put("traceId", "changed");

        assertThat(event.tag("traceId")).isEqualTo("t-1");
        assertThat(event.tag("missing")).isNull();
    }

    @Test
    @DisplayName("自定义 eventId / occurredAt / silentWindow / async 原样保留")
    void shouldKeepExplicitValues() {
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .eventId("evt_custom")
                .occurredAt(123L)
                .silentWindow(java.time.Duration.ofMinutes(5))
                .async(false)
                .title("标题")
                .build();

        assertThat(event.eventId()).isEqualTo("evt_custom");
        assertThat(event.occurredAt()).isEqualTo(123L);
        assertThat(event.silentWindow()).hasMinutes(5);
        assertThat(event.async()).isFalse();
        assertThat(event.title()).isEqualTo("标题");
        assertThat(event.toString()).contains("SCENE_A").contains("evt_custom");
    }
}
