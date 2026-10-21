package io.github.pkyit.notifyx.core.exception;

/**
 * NotifyX 统一异常基类，非受检。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class NotifyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 描述
     */
    public NotifyException(String message) {
        super(message);
    }

    /**
     * 构造异常。
     *
     * @param message 描述
     * @param cause 原因
     */
    public NotifyException(String message, Throwable cause) {
        super(message, cause);
    }
}