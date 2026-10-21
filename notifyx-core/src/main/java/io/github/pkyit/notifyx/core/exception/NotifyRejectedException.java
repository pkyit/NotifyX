package io.github.pkyit.notifyx.core.exception;

import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 事件入队被拒（队列满且优先级不足以替换）。携带被丢弃事件的摘要。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class NotifyRejectedException extends NotifyException {

    private static final long serialVersionUID = 1L;

    private final transient NotifyEvent event;
    private final String reason;

    public NotifyRejectedException(NotifyEvent event, String reason) {
        super("event rejected: " + reason + " (scene=" + event.scene() + ", eventId=" + event.eventId() + ")");
        this.event = event;
        this.reason = reason;
    }

    public NotifyEvent event() {
        return event;
    }

    public String reason() {
        return reason;
    }
}