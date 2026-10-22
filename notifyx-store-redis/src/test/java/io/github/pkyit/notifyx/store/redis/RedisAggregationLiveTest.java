package io.github.pkyit.notifyx.store.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 真实 Redis 上的跨实例聚合验证：多个实例共用一个窗口，只有抢到锁的实例发摘要。
 *
 * <p>仅当设置 {@code NOTIFYX_REDIS_HOST} 时执行；窗口取 5s，避免 port-forward 延迟干扰。</p>
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_REDIS_HOST", matches = ".+")
class RedisAggregationLiveTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        String host = System.getenv("NOTIFYX_REDIS_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("NOTIFYX_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
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
    @DisplayName("单实例窗口内计数 3 条、2 个实例，窗口结束发一条摘要")
    void shouldAggregateWithinWindow() throws InterruptedException {
        String prefix = "nx:aggtest:" + UUID.randomUUID().toString().replace("-", "") + ":";
        List<NotifyEvent> summaries = new CopyOnWriteArrayList<>();
        RedisAggregator aggregator = new RedisAggregator(template, prefix,
                new RedisAggregator.Config(true, Duration.ofSeconds(5), 1000));
        aggregator.sink(summaries::add);

        aggregator.record(event("SCENE_A", "B1", "reason=a"), Level.ERROR, "dedupe-key", "instance-1");
        aggregator.record(event("SCENE_A", "B1", "reason=b"), Level.ERROR, "dedupe-key", "instance-2");
        aggregator.record(event("SCENE_A", "B1", "reason=c"), Level.ERROR, "dedupe-key", "instance-2");

        waitFor(() -> summaries.size() >= 1, 12_000);
        aggregator.close();

        assertThat(summaries).hasSize(1);
        Map<String, Object> params = summaries.get(0).params();
        assertThat(params.get("aggregateCount")).isEqualTo(3L);
        assertThat(params.get("aggregateInstances")).isEqualTo(2);
        assertThat(summaries.get(0).scene()).isEqualTo("SCENE_A");
        assertThat(summaries.get(0).tag("aggregate")).isEqualTo("true");
    }

    @Test
    @DisplayName("两个实例同时记录，抢锁保证总共只有一条摘要")
    void shouldEmitOnlyOnceAcrossInstances() throws InterruptedException {
        String prefix = "nx:aggtest:" + UUID.randomUUID().toString().replace("-", "") + ":";
        List<NotifyEvent> summaries = new CopyOnWriteArrayList<>();
        RedisAggregator first = new RedisAggregator(template, prefix,
                new RedisAggregator.Config(true, Duration.ofSeconds(5), 1000));
        RedisAggregator second = new RedisAggregator(template, prefix,
                new RedisAggregator.Config(true, Duration.ofSeconds(5), 1000));
        first.sink(summaries::add);
        second.sink(summaries::add);

        first.record(event("SCENE_B", "B2", "reason=1"), Level.ERROR, "dedupe-key-2", "instance-1");
        second.record(event("SCENE_B", "B2", "reason=2"), Level.ERROR, "dedupe-key-2", "instance-2");

        waitFor(() -> summaries.size() >= 1, 12_000);
        Thread.sleep(1500);
        first.close();
        second.close();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).params().get("aggregateCount")).isEqualTo(2L);
    }

    private static NotifyEvent event(String scene, String bizId, String param) {
        return NotifyEvent.builder(scene)
                .level(Level.ERROR)
                .bizId(bizId)
                .param("detail", param)
                .build();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
    }
}
