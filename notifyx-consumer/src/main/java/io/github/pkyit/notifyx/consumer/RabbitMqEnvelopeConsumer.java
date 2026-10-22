package io.github.pkyit.notifyx.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * RabbitMQ 报文消费者（{@code notifyx.consumer.transport=rabbitmq}）。
 *
 * <p>与 Kafka consumer 同语义：解码 → 幂等 → 重建事件 → 完整责任链。</p>
 *
 * @since 1.0
 */
@Component
@ConditionalOnProperty(prefix = "notifyx.consumer", name = "transport", havingValue = "rabbitmq")
public class RabbitMqEnvelopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqEnvelopeConsumer.class);

    private final NotifyKit notifyKit;
    private final IdempotencyStore idempotencyStore;

    /**
     * 构造消费者。
     *
     * @param notifyKit 门面
     * @param idempotencyStore 幂等存储
     */
    public RabbitMqEnvelopeConsumer(NotifyKit notifyKit, IdempotencyStore idempotencyStore) {
        this.notifyKit = notifyKit;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 消费一条报文。
     *
     * @param message RabbitMQ 消息
     */
    @RabbitListener(queues = "${notifyx.consumer.rabbitmq.queue:notifyx-events}")
    public void onMessage(Message message) {
        String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
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
            log.warn("notifyx consumer rabbit dispatch failed eventId={}: {}", envelope.eventId(), ex.toString());
        }
    }
}
