package io.github.pkyit.notifyx.core.exception;

/**
 * 总线发布异常（设计书 §11.1）。
 *
 * @since 1.0
 */
public class BusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 信息
     */
    public BusException(String message) {
        super(message);
    }

    /**
     * 构造异常。
     *
     * @param message 信息
     * @param cause 原因
     */
    public BusException(String message, Throwable cause) {
        super(message, cause);
    }
}
