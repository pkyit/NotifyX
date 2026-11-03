package io.github.pkyit.notifyx.bus.rabbitmq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import io.github.pkyit.notifyx.core.exception.BusException;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.spi.NotifyBus;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * RabbitMQ producer（设计书 §11.2）：durable queue + persistent message + publisher confirm。
 *
 * <p>channel 非线程安全，publish 串行化；失败抛 {@link BusException} 由 {@code MqNotifyKit} 落盘。</p>
 *
 * @since 1.0
 */
public final class RabbitMqNotifyBus implements NotifyBus {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqNotifyBus.class);

    /** 死信计数头（consumer 侧退避重试使用，与设计书 §11.2 对齐）。 */
    static final String RETRY_COUNT_HEADER = "x-retry-count";

    /** 死信队列的 routing key（重试耗尽后消息在此落库，设计书 §11.2/§11.4）。 */
    static final String DEAD_ROUTING_KEY = "dead";

    /** 固定三级退避（设计书 §11.2）：{@code retry.5s / retry.1m / retry.5m}。 */
    static final List<RetryLevel> RETRY_LEVELS = List.of(
            new RetryLevel("5s", 5_000L),
            new RetryLevel("1m", 60_000L),
            new RetryLevel("5m", 300_000L));

    private final RabbitMqBusProperties properties;
    private final ConnectionFactory factory;
    private final Object lock = new Object();
    private Connection connection;
    private Channel channel;

    /**
     * 按配置构造（懒连接）。
     *
     * @param properties 配置
     */
    public RabbitMqNotifyBus(RabbitMqBusProperties properties) {
        this(properties, createFactory(properties));
    }

    /**
     * 注入 ConnectionFactory（测试用）。
     *
     * @param properties 配置
     * @param factory 连接工厂
     */
    RabbitMqNotifyBus(RabbitMqBusProperties properties, ConnectionFactory factory) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.factory = java.util.Objects.requireNonNull(factory, "factory");
    }

    @Override
    public String name() {
        return "rabbitmq";
    }

    @Override
    public void publish(NotifyEnvelope envelope) throws BusException {
        byte[] body = NotifyEnvelopeCodec.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            try {
                ensureChannel();
                AMQP.BasicProperties messageProperties = new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .contentEncoding("UTF-8")
                        .deliveryMode(2)
                        .messageId(envelope.dedupeKey())
                        .timestamp(new Date())
                        .build();
                channel.basicPublish("", properties.getQueue(), messageProperties, body);
                channel.waitForConfirmsOrDie(properties.getConfirmTimeout().toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                closeQuietly();
                throw new BusException("rabbitmq publish interrupted", ex);
            } catch (Exception ex) {
                closeQuietly();
                throw new BusException("rabbitmq publish failed: " + ex.getMessage(), ex);
            }
        }
    }

    private void ensureChannel() throws Exception {
        if (channel != null && channel.isOpen() && connection != null && connection.isOpen()) {
            return;
        }
        closeQuietly();
        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.confirmSelect();
        declareTopology(channel);
    }

    /**
     * 声明主队列（退避重试开启时挂死信交换与死信 routing key）+ 多级 TTL 重试队列与绑定 +
     * durable 死信队列（重试耗尽后消息落此队列，设计书 §11.2/§11.4）。声明幂等，
     * producer 与 consumer 可重复调用；关闭重试时仅声明 durable 主队列（与 1.0.0 一致）。
     *
     * @param channel 通道
     */
    private void declareTopology(Channel channel) throws IOException {
        String exchange = null;
        Map<String, Object> args = null;
        if (properties.getRetry().isEnabled()) {
            exchange = properties.getRetry().getExchange();
            args = Map.of(
                    "x-dead-letter-exchange", exchange,
                    "x-dead-letter-routing-key", DEAD_ROUTING_KEY);
        }
        channel.queueDeclare(properties.getQueue(), true, false, false, args);
        if (exchange == null) {
            return;
        }
        channel.exchangeDeclare(exchange, "direct", true);
        for (RetryLevel level : RETRY_LEVELS) {
            String queue = exchange + "." + level.label();
            Map<String, Object> waitArgs = new LinkedHashMap<>();
            waitArgs.put("x-message-ttl", level.ttlMillis());
            waitArgs.put("x-dead-letter-exchange", "");
            waitArgs.put("x-dead-letter-routing-key", properties.getQueue());
            channel.queueDeclare(queue, true, false, false, waitArgs);
            channel.queueBind(queue, exchange, level.label());
        }
        String deadQueue = exchange + ".dead";
        channel.queueDeclare(deadQueue, true, false, false, null);
        channel.queueBind(deadQueue, exchange, DEAD_ROUTING_KEY);
    }

    private void closeQuietly() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ex) {
            log.debug("notifyx rabbitmq channel close failed: {}", ex.toString());
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ex) {
            log.debug("notifyx rabbitmq connection close failed: {}", ex.toString());
        }
        channel = null;
        connection = null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeQuietly();
        }
    }

    private static ConnectionFactory createFactory(RabbitMqBusProperties properties) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setUsername(properties.getUsername());
        factory.setPassword(properties.getPassword());
        factory.setVirtualHost(properties.getVirtualHost());
        factory.setConnectionTimeout((int) properties.getConnectionTimeout().toMillis());
        return factory;
    }

    /**
     * 单级退避定义：标签（作为重试交换的 routing key 与队列名后缀）+ TTL 毫秒。
     *
     * @param label 标签，如 {@code 5s}
     * @param ttlMillis 消息 TTL
     */
    record RetryLevel(String label, long ttlMillis) {
    }
}
