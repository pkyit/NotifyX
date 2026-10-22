package io.github.pkyit.notifyx.store.redis;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.RateLimiter;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.MemoryRateLimiter;

/**
 * Redis 存储自动装配（T1 能力梯度）。
 *
 * <p>仅当 classpath 有 {@link StringRedisTemplate} 且存在对应 Bean、且
 * {@code notifyx.store.redis.enabled=true}（默认）时生效；必须排在
 * {@code NotifyxAutoConfiguration} 之前，才能让后者按 {@code @ConditionalOnMissingBean} 让位。</p>
 *
 * <p>Redis 故障由 {@link FallbackDedupeStore}/{@link FallbackRateLimiter} 降级到内存，
 * 不阻塞通知。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(after = RedisAutoConfiguration.class,
        beforeName = "io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "notifyx.store.redis", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(RedisStoreProperties.class)
public class RedisStoresAutoConfiguration {

    /**
     * Redis 去重（带内存降级）。
     *
     * @param template Redis 模板
     * @param properties 配置
     * @return 去重存储
     */
    @Bean
    @ConditionalOnMissingBean(DedupeStore.class)
    public DedupeStore redisDedupeStore(StringRedisTemplate template, RedisStoreProperties properties) {
        return new FallbackDedupeStore(new RedisDedupeStore(template, properties.getKeyPrefix()),
                new MemoryDedupeStore(), properties.getFailureThreshold(), properties.getOpenDuration());
    }

    /**
     * Redis 限流（带内存降级）。
     *
     * @param template Redis 模板
     * @param properties 配置
     * @return 限流器
     */
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter redisRateLimiter(StringRedisTemplate template, RedisStoreProperties properties) {
        return new FallbackRateLimiter(new RedisRateLimiter(template, properties.getKeyPrefix()),
                new MemoryRateLimiter(), properties.getFailureThreshold(), properties.getOpenDuration());
    }
}
