package io.github.pkyit.notifyx.bus.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 真实 RabbitMQ 集成测试：发布后从队列 basicGet 到同一报文。
 *
 * <pre>
 * NOTIFYX_RABBITMQ_HOST=127.0.0.1 NOTIFYX_RABBITMQ_PORT=15672 \
 *   mvn -B -pl notifyx-bus-rabbitmq test -Dtest=RabbitMqBusLiveTest
 * </pre>
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_RABBITMQ_HOST", matches = ".+")
class RabbitMqBusLiveTest {

    @Test
    @DisplayName("真实 RabbitMQ：发布 persistent 报文后可从队列消费")
    void shouldPublishAndConsume() throws Exception {
        RabbitMqBusProperties properties = new RabbitMqBusProperties();
        properties.setHost(System.getenv("NOTIFYX_RABBITMQ_HOST"));
        properties.setPort(Integer.parseInt(System.getenv().getOrDefault("NOTIFYX_RABBITMQ_PORT", "5672")));
        properties.setQueue("notifyx-events-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        RabbitMqNotifyBus bus = new RabbitMqNotifyBus(properties);
        NotifyEnvelope envelope = NotifyEnvelope.of(
                NotifyEvent.builder("SCENE_LIVE").level(Level.ERROR).bizId("B1").param("k", "v").build(),
                "sha256:rabbit-live", null, null, "inst", 0, System.currentTimeMillis());

        bus.publish(envelope);
        bus.close();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.getHost());
        factory.setPort(properties.getPort());
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.queueDeclarePassive(properties.getQueue());
            GetResponse response = null;
            long deadline = System.currentTimeMillis() + 10_000L;
            while (response == null && System.currentTimeMillis() < deadline) {
                response = channel.basicGet(properties.getQueue(), true);
                if (response == null) {
                    Thread.sleep(200);
                }
            }
            assertThat(response).isNotNull();
            String body = new String(response.getBody(), StandardCharsets.UTF_8);
            assertThat(response.getProps().getMessageId()).isEqualTo("sha256:rabbit-live");
            assertThat(body).contains("\"scene\":\"SCENE_LIVE\"");
        }
    }
}
