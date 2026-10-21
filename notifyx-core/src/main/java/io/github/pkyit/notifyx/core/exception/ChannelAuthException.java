package io.github.pkyit.notifyx.core.exception;

/**
 * 渠道认证/鉴权失败，映射为不可重试。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class ChannelAuthException extends ChannelException {

    private static final long serialVersionUID = 1L;

    public ChannelAuthException(String channel, String message) {
        super(channel, message);
    }

    public ChannelAuthException(String channel, String message, Throwable cause) {
        super(channel, message, cause);
    }
}
