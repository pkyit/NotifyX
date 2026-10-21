package io.github.pkyit.notifyx.core.exception;

/**
 * 渠道协议错误（对方 API 改版、报文不兼容），映射为不可重试。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class ChannelProtocolException extends ChannelException {

    private static final long serialVersionUID = 1L;

    public ChannelProtocolException(String channel, String message) {
        super(channel, message);
    }

    public ChannelProtocolException(String channel, String message, Throwable cause) {
        super(channel, message, cause);
    }
}