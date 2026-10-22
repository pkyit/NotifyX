package io.github.pkyit.notifyx.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * Redis 聚合器单元测试：窗口计数、抢锁、摘要字段、异常吞掉。
 *
 * @since 1.0
 */
@SuppressWarnings("unchecked")
class RedisAggregatorTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
    private final SetOperations<String, String> setOps = mock(SetOperations.class);

    private void wire() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(template.opsForHash()).thenReturn(hashOps);
        when(template.opsForSet()).thenReturn(setOps);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
    }

    @Test
    @DisplayName("窗口结束抢锁成功：读出计数/实例数/样本并投递摘要")
    void shouldEmitSummaryWhenLockAcquired() throws InterruptedException {
        wire();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(hashOps.entries(anyString())).thenReturn(Map.of(
                "count", "3", "scene", "SCENE_A", "firstAt", "1000", "lastAt", "2000",
                "level", "ERROR", "firstSample", "a", "lastSample", "b"));
        when(setOps.size(anyString())).thenReturn(2L);
        List<NotifyEvent> summaries = new CopyOnWriteArrayList<>();
        RedisAggregator aggregator = new RedisAggregator(template, "nx:",
                new RedisAggregator.Config(true, Duration.ofMillis(80), 100));
        aggregator.sink(summaries::add);

        aggregator.record(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build(), Level.ERROR, "key", "i1");
        await(() -> summaries.size() == 1);
        aggregator.close();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).scene()).isEqualTo("SCENE_A");
        assertThat(summaries.get(0).params().get("aggregateCount")).isEqualTo(3L);
        assertThat(summaries.get(0).params().get("aggregateInstances")).isEqualTo(2);
        assertThat(summaries.get(0).tag("aggregate")).isEqualTo("true");
    }

    @Test
    @DisplayName("抢锁失败：不投递摘要")
    void shouldNotEmitWhenLockHeld() throws InterruptedException {
        wire();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        List<NotifyEvent> summaries = new CopyOnWriteArrayList<>();
        RedisAggregator aggregator = new RedisAggregator(template, "nx:",
                new RedisAggregator.Config(true, Duration.ofMillis(50), 100));
        aggregator.sink(summaries::add);

        aggregator.record(NotifyEvent.builder("SCENE_A").build(), Level.WARN, "key", "i1");
        Thread.sleep(300);
        aggregator.close();

        assertThat(summaries).isEmpty();
    }

    @Test
    @DisplayName("Redis 记录异常只吞掉，不影响调用方")
    void shouldSwallowRecordFailure() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        RedisAggregator aggregator = new RedisAggregator(template, "nx:",
                new RedisAggregator.Config(true, Duration.ofSeconds(1), 100));

        aggregator.record(NotifyEvent.builder("SCENE_A").build(), Level.WARN, "key", "i1");
        aggregator.close();

        verify(template, never()).opsForValue();
    }

    @Test
    @DisplayName("enabled=false 时不访问 Redis")
    void shouldSkipWhenDisabled() {
        RedisAggregator aggregator = new RedisAggregator(template, "nx:",
                new RedisAggregator.Config(false, Duration.ofSeconds(1), 100));

        aggregator.record(NotifyEvent.builder("SCENE_A").build(), Level.WARN, "key", "i1");
        aggregator.close();

        verify(template, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("空 hash（窗口数据已过期）不投递摘要")
    void shouldSkipEmptyHash() throws InterruptedException {
        wire();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(hashOps.entries(anyString())).thenReturn(Map.of());
        List<NotifyEvent> summaries = new CopyOnWriteArrayList<>();
        RedisAggregator aggregator = new RedisAggregator(template, "nx:",
                new RedisAggregator.Config(true, Duration.ofMillis(50), 100));
        aggregator.sink(summaries::add);

        aggregator.record(NotifyEvent.builder("SCENE_A").build(), Level.WARN, "key", "i1");
        Thread.sleep(300);
        aggregator.close();

        assertThat(summaries).isEmpty();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
