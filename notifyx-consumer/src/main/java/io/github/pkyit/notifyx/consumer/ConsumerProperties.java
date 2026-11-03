package io.github.pkyit.notifyx.consumer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * consumer 配置（{@code notifyx.consumer.*}）。
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.consumer")
public class ConsumerProperties {

    /** 传输类型：kafka | rabbitmq（默认 kafka）。 */
    private String transport = "kafka";

    /** 订阅 topic。 */
    private String topic = "notifyx-events";

    /** 幂等 TTL。 */
    private Duration idempotencyTtl = Duration.ofHours(24);

    /** 内存幂等容量上限。 */
    private int maxEntries = 100_000;

    /** 幂等实现：memory | redis | jdbc。 */
    private String idempotencyStore = "memory";

    /** JDBC 幂等 URL（仅 jdbc 模式）。 */
    private String jdbcUrl;

    /** JDBC 用户名。 */
    private String jdbcUser;

    /** JDBC 密码。 */
    private String jdbcPassword;

    /** RabbitMQ 消费配置（transport=rabbitmq 时生效）。 */
    private Rabbitmq rabbitmq = new Rabbitmq();

    /**
     * 传输类型。
     *
     * @return {@code kafka} 或 {@code rabbitmq}
     */
    public String getTransport() {
        return transport;
    }

    /**
     * 设置传输类型。
     *
     * @param transport {@code kafka} 或 {@code rabbitmq}
     */
    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public String getIdempotencyStore() {
        return idempotencyStore;
    }

    public void setIdempotencyStore(String idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUser() {
        return jdbcUser;
    }

    public void setJdbcUser(String jdbcUser) {
        this.jdbcUser = jdbcUser;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    public void setRabbitmq(Rabbitmq rabbitmq) {
        this.rabbitmq = rabbitmq;
    }

    /**
     * RabbitMQ 消费配置（{@code notifyx.consumer.rabbitmq.*}）。
     *
     * @since 1.0.1
     */
    public static class Rabbitmq {

        /** 订阅队列名（与 {@code @RabbitListener} 引用一致）。 */
        private String queue = "notifyx-events";

        /** 是否启用退避重试（死信 + 多级 TTL，设计书 §11.2），默认关。 */
        private boolean retryEnabled = false;

        /** 重试交换（死信交换）名。 */
        private String retryExchange = "notifyx.retry";

        public String getQueue() {
            return queue;
        }

        public void setQueue(String queue) {
            this.queue = queue;
        }

        public boolean isRetryEnabled() {
            return retryEnabled;
        }

        public void setRetryEnabled(boolean retryEnabled) {
            this.retryEnabled = retryEnabled;
        }

        public String getRetryExchange() {
            return retryExchange;
        }

        public void setRetryExchange(String retryExchange) {
            this.retryExchange = retryExchange;
        }
    }
}
