package io.github.pkyit.notifyx.autoconfigure;

import java.time.Instant;

import io.github.pkyit.notifyx.core.AlertDispatcher;
import io.github.pkyit.notifyx.core.AlertEvent;
import io.github.pkyit.notifyx.core.AlertLevel;

/**
 * 面向业务的流式告警入口。
 *
 * <pre>{@code
 * alertTemplate.error("支付回调失败")
 *         .content("orderId=" + orderId + ", channel=wxpay")
 *         .tag("orderId", orderId)
 *         .send();
 * }</pre>
 *
 * <p>本类只负责拼装 {@link AlertEvent}，投递策略全部交给 {@link AlertDispatcher}。</p>
 */
public class AlertTemplate {

    private final AlertDispatcher dispatcher;
    private final String defaultSource;

    public AlertTemplate(AlertDispatcher dispatcher, String defaultSource) {
        this.dispatcher = dispatcher;
        this.defaultSource = defaultSource == null || defaultSource.isBlank() ? "application" : defaultSource;
    }

    public AlertBuilder info(String title) {
        return level(AlertLevel.INFO, title);
    }

    public AlertBuilder warn(String title) {
        return level(AlertLevel.WARN, title);
    }

    public AlertBuilder error(String title) {
        return level(AlertLevel.ERROR, title);
    }

    public AlertBuilder critical(String title) {
        return level(AlertLevel.CRITICAL, title);
    }

    public AlertBuilder level(AlertLevel level, String title) {
        return new AlertBuilder(level, title);
    }

    /**
     * 直接投递一个已构建好的事件。
     */
    public void send(AlertEvent event) {
        dispatcher.dispatch(event);
    }

    /**
     * 事件构建器，{@link #send()} 之后即完成投递（异步）。
     */
    public final class AlertBuilder {

        private final AlertEvent.Builder delegate;

        private AlertBuilder(AlertLevel level, String title) {
            this.delegate = AlertEvent.builder(level, title).source(defaultSource);
        }

        public AlertBuilder content(String content) {
            delegate.content(content);
            return this;
        }

        public AlertBuilder source(String source) {
            delegate.source(source);
            return this;
        }

        public AlertBuilder tag(String key, String value) {
            delegate.tag(key, value);
            return this;
        }

        public AlertBuilder attribute(String key, Object value) {
            delegate.attribute(key, value);
            return this;
        }

        public AlertBuilder fingerprint(String fingerprint) {
            delegate.fingerprint(fingerprint);
            return this;
        }

        public AlertBuilder timestamp(Instant timestamp) {
            delegate.timestamp(timestamp);
            return this;
        }

        public AlertEvent build() {
            return delegate.build();
        }

        public void send() {
            dispatcher.dispatch(build());
        }
    }
}
