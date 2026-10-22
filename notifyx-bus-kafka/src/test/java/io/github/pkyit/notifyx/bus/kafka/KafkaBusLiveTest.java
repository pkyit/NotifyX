package io.github.pkyit.notifyx.bus.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 真实 Kafka 集成测试：设置 {@code NOTIFYX_KAFKA_BOOTSTRAP} 后执行。
 *
 * <pre>
 * NOTIFYX_KAFKA_BOOTSTRAP=127.0.0.1:19092 \
 *   mvn -B -pl notifyx-bus-kafka test -Dtest=KafkaBusLiveTest
 * </pre>
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_KAFKA_BOOTSTRAP", matches = ".+")
class KafkaBusLiveTest {

    @Test
    @DisplayName("真实 Kafka：发布报文后按 dedupeKey 消费到同一 JSON")
    void shouldPublishAndConsume() throws Exception {
        String bootstrap = System.getenv("NOTIFYX_KAFKA_BOOTSTRAP");
        String topic = "notifyx-events-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (AdminClient admin = AdminClient.create(adminProperties(bootstrap))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }

        KafkaBusProperties properties = new KafkaBusProperties();
        properties.setBootstrapServers(bootstrap);
        properties.setTopic(topic);
        KafkaNotifyBus bus = new KafkaNotifyBus(properties);
        NotifyEnvelope envelope = NotifyEnvelope.of(
                NotifyEvent.builder("SCENE_LIVE").level(Level.ERROR).bizId("B1").param("k", "v").build(),
                "sha256:live", "trace-1", "span-1", "inst-1", 0, System.currentTimeMillis());
        bus.publish(envelope);
        bus.close();

        Properties consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", bootstrap);
        consumerProperties.put("group.id", "notifyx-live-" + UUID.randomUUID());
        consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("auto.offset.reset", "earliest");
        consumerProperties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    assertThat(record.key()).isEqualTo("sha256:live");
                    assertThat(record.value()).contains("\"scene\":\"SCENE_LIVE\"");
                    return;
                }
            }
        }
        throw new AssertionError("no record consumed from " + topic);
    }

    private static Properties adminProperties(String bootstrap) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrap);
        properties.put("request.timeout.ms", "5000");
        return properties;
    }
}
