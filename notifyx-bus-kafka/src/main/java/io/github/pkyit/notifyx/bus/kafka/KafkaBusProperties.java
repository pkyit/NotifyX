package io.github.pkyit.notifyx.bus.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka 总线配置（{@code notifyx.bus.kafka.*}）。
 *
 * <p>@implNote 可变 POJO，仅在启动期绑定。</p>
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.bus.kafka")
public class KafkaBusProperties {

    /** broker 地址，多个用逗号分隔。 */
    private String bootstrapServers = "localhost:9092";

    /** 事件 topic。 */
    private String topic = "notifyx-events";

    /** 发送确认；默认 all。 */
    private String acks = "all";

    /** producer 幂等（Kafka 侧去重）。 */
    private boolean idempotence = true;

    /** 单条发送超时。 */
    private Duration deliveryTimeout = Duration.ofSeconds(10);

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getAcks() {
        return acks;
    }

    public void setAcks(String acks) {
        this.acks = acks;
    }

    public boolean isIdempotence() {
        return idempotence;
    }

    public void setIdempotence(boolean idempotence) {
        this.idempotence = idempotence;
    }

    public Duration getDeliveryTimeout() {
        return deliveryTimeout;
    }

    public void setDeliveryTimeout(Duration deliveryTimeout) {
        this.deliveryTimeout = deliveryTimeout;
    }
}
