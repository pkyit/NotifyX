package io.github.pkyit.notifyx.autoconfigure.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.AlertChannel;
import io.github.pkyit.notifyx.core.AlertEvent;

/**
 * 内置日志通道：把告警写入专门命名的 logger，便于 ELK/Loki 单独采集与告警。
 *
 * <p>logger 名称固定为 {@code ALERT}，可通过日志框架配置独立输出文件与格式。</p>
 */
public class LoggingAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger("ALERT");

    @Override
    public String name() {
        return "log";
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public void send(AlertEvent event) {
        String message = format(event);
        switch (event.level()) {
            case INFO -> log.info(message);
            case WARN -> log.warn(message);
            case ERROR, CRITICAL -> log.error(message);
        }
    }

    private String format(AlertEvent event) {
        StringBuilder builder = new StringBuilder(128)
                .append('[').append(event.level()).append("] ")
                .append(event.title());
        if (!event.content().isBlank()) {
            builder.append(" | ").append(event.content());
        }
        if (!event.tags().isEmpty()) {
            builder.append(" | tags=").append(event.tags());
        }
        return builder.append(" | source=").append(event.source()).toString();
    }
}
