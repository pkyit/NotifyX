package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 去重存储测试（设计书 18.2 的 T4）。
 *
 * @since 1.0
 */
class MemoryDedupeStoreTest {

    @Test
    @DisplayName("窗口内第二次获取失败")
    void shouldRejectWithinWindow() {
        MemoryDedupeStore store = new MemoryDedupeStore();
        assertThat(store.tryAcquire("k1", Duration.ofMinutes(5))).isTrue();
        assertThat(store.tryAcquire("k1", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    @DisplayName("T4：释放后同一指纹可再次触发")
    void shouldAllowAgainAfterRelease() {
        MemoryDedupeStore store = new MemoryDedupeStore();
        assertThat(store.tryAcquire("k1", Duration.ofMinutes(5))).isTrue();
        store.release("k1");
        assertThat(store.tryAcquire("k1", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("window 为 0 时视为不占位")
    void zeroWindowShouldNotBlock() {
        MemoryDedupeStore store = new MemoryDedupeStore();
        assertThat(store.tryAcquire("k1", Duration.ZERO)).isTrue();
    }
}