package io.github.pkyit.notifyx.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * 装配优先级测试：Redis 自动装配必须排在 {@code NotifyxAutoConfiguration} 之前，
 * 否则后者的 {@code @ConditionalOnMissingBean} 会先创建内存实现。
 *
 * @since 1.0
 */
class RedisBeansTakePrecedenceTest {

    @Test
    @DisplayName("同时存在时，管线使用 Redis 去重/限流而不是内存实现")
    void redisBeansShouldWin() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisStoresAutoConfiguration.class, NotifyxAutoConfiguration.class))
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,
                        () -> mock(org.springframework.data.redis.core.StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DedupeStore.class)).isInstanceOf(FallbackDedupeStore.class);
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(FallbackRateLimiter.class);
                });
    }

    @Test
    @DisplayName("没有 Redis 模板时，管线仍使用内存实现（T0 不退化）")
    void memoryShouldRemainWithoutRedis() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisStoresAutoConfiguration.class, NotifyxAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DedupeStore.class))
                            .isInstanceOf(io.github.pkyit.notifyx.core.support.MemoryDedupeStore.class);
                    assertThat(context.getBean(RateLimiter.class))
                            .isInstanceOf(io.github.pkyit.notifyx.core.support.MemoryRateLimiter.class);
                });
    }

    @Test
    @DisplayName("notifyx.aggregate.enabled=true 且有 Redis 时使用跨实例聚合")
    void redisAggregatorShouldWinWhenEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisStoresAutoConfiguration.class, NotifyxAutoConfiguration.class))
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,
                        () -> mock(org.springframework.data.redis.core.StringRedisTemplate.class))
                .withPropertyValues("notifyx.aggregate.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(io.github.pkyit.notifyx.core.pipeline.EventAggregator.class))
                            .isInstanceOf(RedisAggregator.class);
                });
    }

    @Test
    @DisplayName("开启聚合但没有 Redis 时仍用 T0 聚合器")
    void t0AggregatorShouldRemainWithoutRedis() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisStoresAutoConfiguration.class, NotifyxAutoConfiguration.class))
                .withPropertyValues("notifyx.aggregate.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(io.github.pkyit.notifyx.core.pipeline.EventAggregator.class))
                            .isInstanceOf(io.github.pkyit.notifyx.core.pipeline.Aggregator.class);
                });
    }
}
