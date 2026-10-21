package io.github.pkyit.notifyx.core.support;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.pkyit.notifyx.core.exception.TemplateRenderException;
import io.github.pkyit.notifyx.core.model.RenderFormat;
import io.github.pkyit.notifyx.core.spi.TemplateEngine;

/**
 * 内置模板引擎：只做 {@code ${key}} 占位替换，支持点号路径，最多递归 3 层。
 *
 * <p>刻意不实现条件与循环——无表达式求值即无注入风险（设计书 16.1）。
 * 变量值统一按 {@code String.valueOf} 处理，null 渲染为空串而非 "null"。</p>
 *
 * <p>@implNote 无状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class SimpleTemplateEngine implements TemplateEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");
    private static final int MAX_DEPTH = 3;

    @Override
    public String name() {
        return "simple";
    }

    @Override
    public String render(String template, Map<String, Object> params, RenderFormat format) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        try {
            String current = template;
            for (int depth = 0; depth < MAX_DEPTH; depth++) {
                Matcher matcher = PLACEHOLDER.matcher(current);
                StringBuilder builder = new StringBuilder();
                boolean replaced = false;
                while (matcher.find()) {
                    Object value = resolve(matcher.group(1), params);
                    matcher.appendReplacement(builder, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
                    replaced = true;
                }
                matcher.appendTail(builder);
                current = builder.toString();
                if (!replaced) {
                    break;
                }
            }
            return current;
        } catch (RuntimeException ex) {
            throw new TemplateRenderException("failed to render template: " + ex.getMessage(), ex);
        }
    }

    private static Object resolve(String path, Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Object current = params;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
