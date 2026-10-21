package io.github.pkyit.notifyx.core.pipeline;

import java.util.Objects;

/**
 * 消息模板。
 *
 * <p>@implNote 不可变 record，线程安全。</p>
 *
 * @param code 模板编码
 * @param title 标题模板，可为 null
 * @param content 正文模板
 * @since 1.0
 */
public record MessageTemplate(String code, String title, String content) {

    /**
     * 规范化构造。
     *
     * @param code 编码
     * @param title 标题
     * @param content 正文
     */
    public MessageTemplate {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(content, "content");
    }
}