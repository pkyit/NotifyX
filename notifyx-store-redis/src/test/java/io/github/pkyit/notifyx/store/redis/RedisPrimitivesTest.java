package io.github.pkyit.notifyx.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 去重/限流的单元测试（不连 Redis）：脚本结果与键前缀。
 *
 * @since 1.0
 */
class RedisPrimitivesTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("去重：脚本返回 1 放行、0 拦截；release 删除键；size 返回 -1")
    void dedupeShouldInterpretScriptResult() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L, 0L);
        RedisDedupeStore store = new RedisDedupeStore(template, "nx:");

        assertThat(store.tryAcquire("sha256:abc", Duration.ofMinutes(1))).isTrue();
        assertThat(store.tryAcquire("sha256:abc", Duration.ofMinutes(1))).isFalse();
        assertThat(store.size()).isEqualTo(-1L);

        store.release("sha256:abc");
        verify(template).delete("nx:dedup:sha256:abc");
    }

    @Test
    @DisplayName("去重：脚本返回 null 视为未获取")
    void dedupeShouldTreatNullAsRejected() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn((Long) null);

        assertThat(new RedisDedupeStore(template, "nx:").tryAcquire("k", Duration.ofSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("去重 TTL 使用毫秒，保留亚秒窗口")
    void dedupeShouldUseMillisecondTtl() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        new RedisDedupeStore(template, "nx:").tryAcquire("k", Duration.ofMillis(100));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(template).execute(any(RedisScript.class), eq(List.of("nx:dedup:k")), args.capture());
        assertThat(args.getValue()).containsExactly("100");
    }

    @Test
    @DisplayName("限流：脚本返回 1/0；permits<=0 直接拒绝；currentCount 透传")
    void rateLimiterShouldInterpretScriptResult() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L, 0L, 2L);
        RedisRateLimiter limiter = new RedisRateLimiter(template, "nx:");

        assertThat(limiter.tryAcquire("scene:A", 2, Duration.ofSeconds(1))).isTrue();
        assertThat(limiter.tryAcquire("scene:A", 2, Duration.ofSeconds(1))).isFalse();
        assertThat(limiter.tryAcquire("scene:A", 0, Duration.ofSeconds(1))).isFalse();
        assertThat(limiter.currentCount("scene:A", Duration.ofSeconds(1))).isEqualTo(2L);
    }

    @Test
    @DisplayName("键前缀按配置拼接")
    void shouldUseConfiguredPrefix() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RedisDedupeStore store = new RedisDedupeStore(template, "test:");

        store.tryAcquire("k", Duration.ofSeconds(1));

        verify(template).execute(any(RedisScript.class), eq(java.util.List.of("test:dedup:k")),
                any(Object[].class));
    }
}
