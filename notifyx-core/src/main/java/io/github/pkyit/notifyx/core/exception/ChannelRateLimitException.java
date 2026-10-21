package io.github.pkyit.notifyx.core.exception;

/**
 * 渠道侧限流，映射为可重试且使用长退避。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class ChannelRateLimitException extends ChannelException {

    private static final long serialVersionUID = 1L;

    public ChannelRateLimitException(String channel, String message) {
        super(channel, message);
    }
}