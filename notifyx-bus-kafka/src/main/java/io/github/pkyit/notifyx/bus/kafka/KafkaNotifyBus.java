package io.github.pkyit.notifyx.bus.kafka;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.exception.BusException;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.spi.NotifyBus;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * Kafka producer（设计书 §11.2）：{@code key=dedupeKey} 保序，value=报文 JSON。
 *
 * <p>{@code acks=all} + {@code enable.idempotence=true}；发送失败抛 {@link BusException}，
 * 由 {@code MqNotifyKit} 落盘兜底。</p>
 *
 * <p>@implNote 线程安全；{@link #close()} 幂等。</p>
 *
 * @since 1.0
 */
public final class KafkaNotifyBus implements NotifyBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotifyBus.class);

    private final Producer<String, String> producer;
    private final String topic;
    private final Duration timeout;

    /**
     * 按配置构造 producer。
     *
     * @param properties 配置
     */
    public KafkaNotifyBus(KafkaBusProperties properties) {
        this(new KafkaProducer<>(kafkaProperties(properties)), properties.getTopic(),
                properties.getDeliveryTimeout());
    }

    /**
     * 测试/自定义 producer 构造。
     *
     * @param producer producer
     * @param topic topic
     * @param timeout 发送超时
     */
    KafkaNotifyBus(Producer<String, String> producer, String topic, Duration timeout) {
        this.producer = java.util.Objects.requireNonNull(producer, "producer");
        this.topic = java.util.Objects.requireNonNull(topic, "topic");
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void publish(NotifyEnvelope envelope) throws BusException {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, envelope.dedupeKey(),
                NotifyEnvelopeCodec.toJson(envelope));
        try {
            producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusException("kafka publish interrupted", ex);
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            throw new BusException("kafka publish failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        try {
            producer.close(timeout);
        } catch (RuntimeException ex) {
            log.warn("notifyx kafka producer close failed: {}", ex.toString());
        }
    }

    private static Properties kafkaProperties(KafkaBusProperties properties) {
        Properties kafka = new Properties();
        kafka.put("bootstrap.servers", properties.getBootstrapServers());
        kafka.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        kafka.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        kafka.put("acks", properties.getAcks());
        kafka.put("enable.idempotence", String.valueOf(properties.isIdempotence()));
        kafka.put("delivery.timeout.ms", String.valueOf(properties.getDeliveryTimeout().toMillis()));
        // delivery.timeout 必须 >= linger.ms + request.timeout.ms；显式调小 request.timeout
        kafka.put("request.timeout.ms", "5000");
        kafka.put("retries", "3");
        kafka.put("max.in.flight.requests.per.connection", "5");
        return kafka;
    }
}
