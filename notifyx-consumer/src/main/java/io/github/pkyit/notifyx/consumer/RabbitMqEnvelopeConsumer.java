package io.github.pkyit.notifyx.consumer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * RabbitMQ 报文消费者（{@code notifyx.consumer.transport=rabbitmq}）。
 *
 * <p>与 Kafka consumer 同语义：解码 → 幂等 → 重建事件 → 完整责任链。开启退避重试时，
 * 消费失败不直接丢弃：释放幂等键后把报文重新投递到多级 TTL 重试队列（设计书 §11.2），
 * 超过重试次数上限则通过 {@link AmqpRejectAndDontRequeueException} 拒绝（进入死信）。</p>
 *
 * @since 1.0
 */
@Component
@ConditionalOnProperty(prefix = "notifyx.consumer", name = "transport", havingValue = "rabbitmq")
public class RabbitMqEnvelopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqEnvelopeConsumer.class);

    /** 死信计数头（与 producer 侧一致，设计书 §11.2）。 */
    static final String RETRY_COUNT_HEADER = "x-retry-count";

    /** 固定三级退避 routing key（对应 {@code retry.5s / retry.1m / retry.5m}）。 */
    private static final List<String> RETRY_LEVEL_KEYS = List.of("5s", "1m", "5m");

    private final NotifyKit notifyKit;
    private final IdempotencyStore idempotencyStore;
    private final boolean retryEnabled;
    private final String retryExchange;

    /**
     * 构造消费者。
     *
     * @param notifyKit 门面
     * @param idempotencyStore 幂等存储
     * @param properties 配置
     */
    public RabbitMqEnvelopeConsumer(NotifyKit notifyKit, IdempotencyStore idempotencyStore,
                                    ConsumerProperties properties) {
        this.notifyKit = notifyKit;
        this.idempotencyStore = idempotencyStore;
        this.retryEnabled = properties.getRabbitmq().isRetryEnabled();
        this.retryExchange = properties.getRabbitmq().getRetryExchange();
    }

    /**
     * 消费一条报文。
     *
     * @param message RabbitMQ 消息
     * @param channel RabbitMQ 通道（用于失败时重投到重试交换）
     */
    @RabbitListener(queues = "#{notifyxRabbitMainQueue.name}")
    public void onMessage(Message message, Channel channel) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        NotifyEnvelope envelope;
        try {
            envelope = NotifyEnvelopeCodec.fromJson(payload);
        } catch (RuntimeException ex) {
            log.error("notifyx consumer cannot decode rabbit envelope, skip: {}", ex.toString());
            return;
        }
        String key = envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank()
                ? envelope.eventId() : envelope.idempotencyKey();
        if (!idempotencyStore.firstSeen(key)) {
            log.info("notifyx consumer duplicate rabbit message, ack: eventId={}", envelope.eventId());
            return;
        }
        try {
            notifyKit.send(envelope.toEvent());
        } catch (RuntimeException ex) {
            int retryCount = readRetryCount(message);
            if (retryEnabled && retryCount < RETRY_LEVEL_KEYS.size()) {
                idempotencyStore.release(key);
                republishForRetry(channel, message, retryCount);
                return;
            }
            log.warn("notifyx consumer rabbit dispatch failed eventId={} retries={}: {}",
                    envelope.eventId(), retryCount, ex.toString());
            throw new AmqpRejectAndDontRequeueException(
                    "notifyx consumer dispatch failed after " + retryCount + " retries", ex);
        }
    }

    private int readRetryCount(Message message) {
        Object header = message.getMessageProperties().getHeader(RETRY_COUNT_HEADER);
        if (header instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private void republishForRetry(Channel channel, Message message, int retryCount) {
        String routingKey = RETRY_LEVEL_KEYS.get(retryCount);
        Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
        headers.put(RETRY_COUNT_HEADER, retryCount + 1);
        String contentType = message.getMessageProperties().getContentType();
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(contentType == null ? "application/json" : contentType)
                .contentEncoding("UTF-8")
                .deliveryMode(2)
                .messageId(message.getMessageProperties().getMessageId())
                .headers(headers)
                .build();
        try {
            channel.basicPublish(retryExchange, routingKey, properties, message.getBody());
        } catch (Exception ex) {
            log.warn("notifyx consumer rabbit republish to retry exchange failed: {}", ex.toString());
            throw new AmqpRejectAndDontRequeueException("notifyx consumer republish failed", ex);
        }
    }
}
