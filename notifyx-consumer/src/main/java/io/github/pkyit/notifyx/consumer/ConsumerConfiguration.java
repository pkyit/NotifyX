package io.github.pkyit.notifyx.consumer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * consumer 装配：幂等存储与属性。
 *
 * @since 1.0
 */
@Configuration
@EnableConfigurationProperties(ConsumerProperties.class)
public class ConsumerConfiguration {

    /**
     * 内存幂等存储（T0；生产可替换为 Redis/JDBC 实现）。
     *
     * @param properties 配置
     * @return 幂等存储
     */
    @Bean
    public IdempotencyStore idempotencyStore(ConsumerProperties properties) {
        return new MemoryIdempotencyStore(properties.getIdempotencyTtl().toMillis(), properties.getMaxEntries());
    }
}
