package io.github.pkyit.notifyx.core.spi;

import java.util.Map;

import io.github.pkyit.notifyx.core.model.RenderFormat;

/**
 * 模板引擎 SPI。
 *
 * <p>内置 {@code simple} 引擎只做占位替换，不支持条件与循环——无表达式求值即无注入风险。</p>
 *
 * <p>@implNote 实现类必须线程安全。</p>
 *
 * @since 1.0
 */
public interface TemplateEngine {

    /**
     * 引擎名，如 {@code simple} / {@code freemarker}。
     *
     * @return 名称
     */
    String name();

    /**
     * 渲染模板。
     *
     * @param template 模板文本
     * @param params 模板变量
     * @param format 目标格式
     * @return 渲染结果，模板为 null 时返回空串
     * @throws io.github.pkyit.notifyx.core.exception.TemplateRenderException 渲染失败时抛出
     */
    String render(String template, Map<String, Object> params, RenderFormat format);

    /**
     * 是否支持条件与循环。
     *
     * @return 是否支持
     */
    default boolean supportsConditionals() {
        return false;
    }
}
