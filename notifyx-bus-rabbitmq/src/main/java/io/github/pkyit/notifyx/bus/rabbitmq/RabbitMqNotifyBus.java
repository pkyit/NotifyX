package io.github.pkyit.notifyx.bus.rabbitmq;

import java.nio.charset.StandardCharsets;
import java.util.Date;

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
        channel.queueDeclare(properties.getQueue(), true, false, false, null);
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
}
