package io.github.pkyit.notifyx.core.exception;

/**
 * 渠道超时，映射为可重试。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class ChannelTimeoutException extends ChannelException {

    private static final long serialVersionUID = 1L;

    public ChannelTimeoutException(String channel, Throwable cause) {
        super(channel, cause);
    }
}
