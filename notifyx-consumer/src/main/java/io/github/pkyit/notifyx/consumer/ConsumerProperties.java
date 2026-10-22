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

    /** 订阅 topic。 */
    private String topic = "notifyx-events";

    /** 幂等 TTL。 */
    private Duration idempotencyTtl = Duration.ofHours(24);

    /** 内存幂等容量上限。 */
    private int maxEntries = 100_000;

    /** 幂等实现：memory | redis | jdbc。 */
    private String idempotencyStore = "memory";

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
}
