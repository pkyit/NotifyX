package io.github.pkyit.notifyx.bus.rabbitmq;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ 总线配置（{@code notifyx.bus.rabbitmq.*}）。
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.bus.rabbitmq")
public class RabbitMqBusProperties {

    /** broker 主机。 */
    private String host = "localhost";

    /** AMQP 端口。 */
    private int port = 5672;

    /** 用户名。 */
    private String username = "guest";

    /** 密码。 */
    private String password = "guest";

    /** vhost。 */
    private String virtualHost = "/";

    /** 队列名（生产者声明 durable 队列）。 */
    private String queue = "notifyx-events";

    /** 连接超时。 */
    private Duration connectionTimeout = Duration.ofSeconds(5);

    /** publisher confirm 超时。 */
    private Duration confirmTimeout = Duration.ofSeconds(5);

    /** 退避重试（死信 + 多级 TTL，设计书 §11.2）。 */
    private Retry retry = new Retry();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    /**
     * 退避重试配置（{@code notifyx.bus.rabbitmq.retry.*}）。
     *
     * @since 1.0.1
     */
    public static class Retry {

        /** 是否启用退避重试（死信 + 多级 TTL），默认关。 */
        private boolean enabled = false;

        /** 重试交换（死信交换）名。 */
        private String exchange = "notifyx.retry";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }
    }
}
