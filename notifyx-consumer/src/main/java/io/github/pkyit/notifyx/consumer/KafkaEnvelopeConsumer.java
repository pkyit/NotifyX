package io.github.pkyit.notifyx.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * Kafka 报文消费者：解码 → 幂等 → 重建事件 → 完整责任链（NotifyKit）。
 *
 * <p>单实例起步，天然避免多消费者重复；重复消息直接 ACK 丢弃（设计书 §11.4）。</p>
 *
 * <p>@implNote 线程安全；解析失败/发送异常都只记录并返回（不阻塞分区）。</p>
 *
 * @since 1.0
 */
@Component
public class KafkaEnvelopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEnvelopeConsumer.class);

    private final NotifyKit notifyKit;
    private final IdempotencyStore idempotencyStore;

    /**
     * 构造消费者。
     *
     * @param notifyKit 门面
     * @param idempotencyStore 幂等存储
     */
    public KafkaEnvelopeConsumer(NotifyKit notifyKit, IdempotencyStore idempotencyStore) {
        this.notifyKit = notifyKit;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 消费一条报文。
     *
     * @param payload 报文 JSON
     */
    @KafkaListener(topics = "${notifyx.consumer.topic:notifyx-events}",
            groupId = "${spring.kafka.consumer.group-id:notifyx-consumer}")
    public void onMessage(String payload) {
        NotifyEnvelope envelope;
        try {
            envelope = NotifyEnvelopeCodec.fromJson(payload);
        } catch (RuntimeException ex) {
            log.error("notifyx consumer cannot decode envelope, skip: {}", ex.toString());
            return;
        }
        String key = envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank()
                ? envelope.eventId() : envelope.idempotencyKey();
        if (!idempotencyStore.firstSeen(key)) {
            log.info("notifyx consumer duplicate message, ack: eventId={}", envelope.eventId());
            return;
        }
        try {
            notifyKit.send(envelope.toEvent());
        } catch (RuntimeException ex) {
            log.warn("notifyx consumer dispatch failed eventId={}: {}", envelope.eventId(), ex.toString());
        }
    }
}
