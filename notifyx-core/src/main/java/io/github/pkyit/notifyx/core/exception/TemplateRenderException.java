package io.github.pkyit.notifyx.core.exception;

/**
 * 模板渲染失败。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class TemplateRenderException extends NotifyException {

    private static final long serialVersionUID = 1L;

    public TemplateRenderException(String message) {
        super(message);
    }

    public TemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}