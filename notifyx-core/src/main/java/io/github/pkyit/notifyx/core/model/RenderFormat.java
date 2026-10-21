package io.github.pkyit.notifyx.core.model;

/**
 * 渲染格式。由渠道通过 {@code NotifyChannel#preferredFormat()} 声明，管线据此渲染（设计书 ADR-004）。
 *
 * <p>@implNote 枚举天然线程安全且不可变。</p>
 *
 * @since 1.0
 */
public enum RenderFormat {

    /** 纯文本，模板引擎只做占位替换。 */
    PLAIN,

    /** Markdown，适用于钉钉、飞书等 IM 卡片。 */
    MARKDOWN,

    /** HTML，适用于邮件正文。 */
    HTML
}
