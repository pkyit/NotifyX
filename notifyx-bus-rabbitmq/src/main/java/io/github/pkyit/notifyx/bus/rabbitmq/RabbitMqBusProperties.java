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
}
