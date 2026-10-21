package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.RenderFormat;

/**
 * 模板引擎测试。
 *
 * @since 1.0
 */
class SimpleTemplateEngineTest {

    private final SimpleTemplateEngine engine = new SimpleTemplateEngine();

    @Test
    @DisplayName("占位替换与 null 渲染为空串")
    void shouldReplacePlaceholders() {
        Map<String, Object> params = new HashMap<>();
        params.put("scene", "ORDER_CREATE_FAIL");
        params.put("bizId", null);

        String rendered = engine.render("场景 ${scene} 业务号 ${bizId}", params, RenderFormat.PLAIN);

        assertThat(rendered).isEqualTo("场景 ORDER_CREATE_FAIL 业务号 ");
    }

    @Test
    @DisplayName("支持点号路径")
    void shouldResolveNestedPath() {
        Map<String, Object> params = Map.of("order", Map.of("id", "SO1"));
        assertThat(engine.render("${order.id}", params, RenderFormat.PLAIN)).isEqualTo("SO1");
    }

    @Test
    @DisplayName("模板为 null 时返回空串")
    void shouldReturnEmptyForNullTemplate() {
        assertThat(engine.render(null, Map.of(), RenderFormat.PLAIN)).isEmpty();
    }
}