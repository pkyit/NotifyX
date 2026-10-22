package io.github.pkyit.notifyx.store.redis;

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
 * 真实 Redis 集成测试：仅当设置 {@code NOTIFYX_REDIS_HOST} 时执行。
 *
 * <pre>
 * $env:NOTIFYX_REDIS_HOST="127.0.0.1"
 * $env:NOTIFYX_REDIS_PORT="16379"
 * mvn -B -pl notifyx-store-redis test -Dtest=RedisStoresLiveTest
 * </pre>
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_REDIS_HOST", matches = ".+")
class RedisStoresLiveTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static String prefix;

    @BeforeAll
    static void connect() {
        String host = System.getenv("NOTIFYX_REDIS_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("NOTIFYX_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        prefix = "nx:test:" + UUID.randomUUID().toString().replace("-", "") + ":";
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("SET NX EX：窗口内只放行一次，release 后可再次放行")
    void dedupeShouldAcquireOnce() {
        RedisDedupeStore store = new RedisDedupeStore(template, prefix);

        assertThat(store.tryAcquire("sha256:abc", Duration.ofMinutes(1))).isTrue();
        assertThat(store.tryAcquire("sha256:abc", Duration.ofMinutes(1))).isFalse();

        store.release("sha256:abc");

        assertThat(store.tryAcquire("sha256:abc", Duration.ofMinutes(1))).isTrue();
    }

    @Test
    @DisplayName("去重窗口过期后可再次放行")
    void dedupeShouldExpire() throws InterruptedException {
        RedisDedupeStore store = new RedisDedupeStore(template, prefix);

        assertThat(store.tryAcquire("sha256:ttl", Duration.ofSeconds(1))).isTrue();
        assertThat(store.tryAcquire("sha256:ttl", Duration.ofSeconds(1))).isFalse();
        Thread.sleep(1300);

        assertThat(store.tryAcquire("sha256:ttl", Duration.ofSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("滑动窗限流：permits 次后拒绝")
    void rateLimiterShouldEnforceWindow() {
        RedisRateLimiter limiter = new RedisRateLimiter(template, prefix);
        Duration window = Duration.ofSeconds(30);

        assertThat(limiter.tryAcquire("scene:ORDER_CREATE_FAIL", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("scene:ORDER_CREATE_FAIL", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("scene:ORDER_CREATE_FAIL", 2, window)).isFalse();
        assertThat(limiter.currentCount("scene:ORDER_CREATE_FAIL", window)).isEqualTo(2);
    }

    @Test
    @DisplayName("滑动窗限流：窗口过期后恢复")
    void rateLimiterShouldExpire() throws InterruptedException {
        RedisRateLimiter limiter = new RedisRateLimiter(template, prefix);
        Duration window = Duration.ofSeconds(1);

        assertThat(limiter.tryAcquire("scene:EXPIRE", 1, window)).isTrue();
        Thread.sleep(2000);

        assertThat(limiter.tryAcquire("scene:EXPIRE", 1, window)).isTrue();
    }

    @Test
    @DisplayName("降级包装在真实 Redis 上正常工作")
    void fallbackWrapperShouldUseRedis() {
        FallbackDedupeStore store = new FallbackDedupeStore(new RedisDedupeStore(template, prefix),
                new io.github.pkyit.notifyx.core.support.MemoryDedupeStore(), 3, Duration.ofSeconds(10));

        assertThat(store.tryAcquire("sha256:wrapper", Duration.ofMinutes(1))).isTrue();
        assertThat(store.tryAcquire("sha256:wrapper", Duration.ofMinutes(1))).isFalse();
        assertThat(store.isDegraded()).isFalse();
    }
}
