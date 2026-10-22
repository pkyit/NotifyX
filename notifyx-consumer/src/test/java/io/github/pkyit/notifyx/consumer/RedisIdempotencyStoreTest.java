package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Redis 幂等单元测试：SET NX 结果、异常降级内存。
 *
 * @since 1.0
 */
@SuppressWarnings("unchecked")
class RedisIdempotencyStoreTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    @Test
    @DisplayName("SET NX 返回 true/false 直接透传")
    void shouldUseSetNxResult() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);
        RedisIdempotencyStore store = new RedisIdempotencyStore(template, Duration.ofHours(24), 100);

        assertThat(store.firstSeen("idem-1")).isTrue();
        assertThat(store.firstSeen("idem-1")).isFalse();
    }

    @Test
    @DisplayName("Redis 异常时降级内存：仍能识别重复，不丢通知")
    void shouldFallbackOnRedisFailure() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        RedisIdempotencyStore store = new RedisIdempotencyStore(template, Duration.ofHours(24), 100);

        assertThat(store.firstSeen("idem-2")).isTrue();
        assertThat(store.firstSeen("idem-2")).isFalse();
    }
}
