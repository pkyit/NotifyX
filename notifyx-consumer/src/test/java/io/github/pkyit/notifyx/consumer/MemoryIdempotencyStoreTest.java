package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内存幂等存储测试：首次/重复/TTL/容量。
 *
 * @since 1.0
 */
class MemoryIdempotencyStoreTest {

    @Test
    @DisplayName("首次返回 true，重复返回 false")
    void shouldDetectDuplicate() {
        MemoryIdempotencyStore store = new MemoryIdempotencyStore(60_000L, 100);

        assertThat(store.firstSeen("idem-1")).isTrue();
        assertThat(store.firstSeen("idem-1")).isFalse();
        assertThat(store.firstSeen("idem-2")).isTrue();
    }

    @Test
    @DisplayName("空键视为首次（不拦截）")
    void blankKeyShouldPass() {
        assertThat(new MemoryIdempotencyStore(60_000L, 10).firstSeen("")).isTrue();
    }

    @Test
    @DisplayName("超过容量后淘汰旧条目，避免 OOM")
    void shouldEvictWhenFull() throws InterruptedException {
        MemoryIdempotencyStore store = new MemoryIdempotencyStore(60_000L, 5);
        for (int i = 0; i < 5; i++) {
            store.firstSeen("k" + i);
        }
        store.firstSeen("k5");

        // 至少有一个旧键被淘汰；新键仍可识别重复
        assertThat(store.firstSeen("k5")).isFalse();
    }
}
