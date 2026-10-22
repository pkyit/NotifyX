package io.github.pkyit.notifyx.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * Redis 自动装配测试：有模板才生效、可关闭、类型正确。
 *
 * @since 1.0
 */
class RedisStoresAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisStoresAutoConfiguration.class));

    @Test
    @DisplayName("存在 StringRedisTemplate 时装配 Redis 去重/限流（带降级包装）")
    void shouldCreateRedisStores() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DedupeStore.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(DedupeStore.class)).isInstanceOf(FallbackDedupeStore.class);
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(FallbackRateLimiter.class);
                });
    }

    @Test
    @DisplayName("没有 StringRedisTemplate 时不装配，保持 T0 内存实现")
    void shouldBackOffWithoutTemplate() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(DedupeStore.class);
            assertThat(context).doesNotHaveBean(RateLimiter.class);
        });
    }

    @Test
    @DisplayName("notifyx.store.redis.enabled=false 时回退内存")
    void shouldBackOffWhenDisabled() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("notifyx.store.redis.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DedupeStore.class);
                    assertThat(context).doesNotHaveBean(RateLimiter.class);
                });
    }

    @Test
    @DisplayName("配置前缀与阈值可绑定")
    void shouldBindProperties() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("notifyx.store.redis.key-prefix=test:", "notifyx.store.redis.failure-threshold=5",
                        "notifyx.store.redis.open-duration=10s")
                .run(context -> {
                    RedisStoreProperties properties = context.getBean(RedisStoreProperties.class);
                    assertThat(properties.getKeyPrefix()).isEqualTo("test:");
                    assertThat(properties.getFailureThreshold()).isEqualTo(5);
                    assertThat(properties.getOpenDuration()).hasSeconds(10);
                });
    }
}
