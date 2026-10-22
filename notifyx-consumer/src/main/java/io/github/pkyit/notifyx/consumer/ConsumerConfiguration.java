package io.github.pkyit.notifyx.consumer;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * consumer 装配：按 {@code notifyx.consumer.idempotency-store} 选择幂等实现。
 *
 * @since 1.0
 */
@Configuration
@EnableConfigurationProperties(ConsumerProperties.class)
public class ConsumerConfiguration {

    /**
     * 内存幂等（默认 T0）。
     *
     * @param properties 配置
     * @return 幂等存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "notifyx.consumer", name = "idempotency-store",
            havingValue = "memory", matchIfMissing = true)
    public IdempotencyStore memoryIdempotencyStore(ConsumerProperties properties) {
        return new MemoryIdempotencyStore(properties.getIdempotencyTtl().toMillis(), properties.getMaxEntries());
    }

    /**
     * Redis 幂等（T1）。
     *
     * @param template Redis 模板
     * @param properties 配置
     * @return 幂等存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "notifyx.consumer", name = "idempotency-store", havingValue = "redis")
    @ConditionalOnBean(StringRedisTemplate.class)
    public IdempotencyStore redisIdempotencyStore(StringRedisTemplate template, ConsumerProperties properties) {
        return new RedisIdempotencyStore(template, properties.getIdempotencyTtl(), properties.getMaxEntries());
    }

    /**
     * JDBC 幂等（T1）。
     *
     * @param dataSource 数据源
     * @return 幂等存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "notifyx.consumer", name = "idempotency-store", havingValue = "jdbc")
    @ConditionalOnBean(DataSource.class)
    public IdempotencyStore jdbcIdempotencyStore(DataSource dataSource) {
        return new JdbcIdempotencyStore(dataSource);
    }
}
