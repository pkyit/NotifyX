package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis 幂等测试：设置 {@code NOTIFYX_REDIS_HOST} 后执行。
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_REDIS_HOST", matches = ".+")
class RedisIdempotencyLiveTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv("NOTIFYX_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("NOTIFYX_REDIS_PORT", "6379")));
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("真实 Redis：SET NX 首次 true、重复 false")
    void shouldUseRedisSetNx() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(template, Duration.ofMinutes(5), 100);
        String key = "live-" + UUID.randomUUID();

        try {
            assertThat(store.firstSeen(key)).isTrue();
            assertThat(store.firstSeen(key)).isFalse();
        } finally {
            template.delete("nx:idem:" + key);
        }
    }
}
