package io.github.pkyit.notifyx.core.exception;

/**
 * 渠道侧失败。保留渠道名与 cause，便于分类重试（设计书 6.6）。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class ChannelException extends NotifyException {

    private static final long serialVersionUID = 1L;

    private final String channel;

    /**
     * 构造异常。
     *
     * @param channel 渠道名
     * @param message 描述
     */
    public ChannelException(String channel, String message) {
        super(message);
        this.channel = channel;
    }

    /**
     * 构造异常。
     *
     * @param channel 渠道名
     * @param cause 原因
     */
    public ChannelException(String channel, Throwable cause) {
        super("channel [" + channel + "] failed: " + (cause == null ? "unknown" : cause.toString()), cause);
        this.channel = channel;
    }

    /**
     * 构造异常。
     *
     * @param channel 渠道名
     * @param message 描述
     * @param cause 原因
     */
    public ChannelException(String channel, String message, Throwable cause) {
        super(message, cause);
        this.channel = channel;
    }

    public String channel() {
        return channel;
    }
}
