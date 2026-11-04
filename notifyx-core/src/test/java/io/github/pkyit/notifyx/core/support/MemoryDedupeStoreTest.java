package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test
    @DisplayName("容量打满且均未过期时拒绝新增，避免 OOM")
    void shouldRejectWhenFullWithLiveEntries() {
        MemoryDedupeStore store = new MemoryDedupeStore(4);
        for (int i = 0; i < 4; i++) {
            assertThat(store.tryAcquire("k" + i, Duration.ofMinutes(5))).isTrue();
        }
        assertThat(store.tryAcquire("overflow", Duration.ofMinutes(5))).isFalse();
        assertThat(store.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("容量打满后过期条目可被清理并恢复接收")
    void shouldRecoverAfterExpiredEntriesEvicted() throws Exception {
        MemoryDedupeStore store = new MemoryDedupeStore(4);
        for (int i = 0; i < 4; i++) {
            assertThat(store.tryAcquire("k" + i, Duration.ofMillis(20))).isTrue();
        }
        assertThat(store.tryAcquire("overflow", Duration.ofMinutes(5))).isFalse();

        Thread.sleep(MemoryDedupeStore.EVICT_INTERVAL_MILLIS + 40);

        assertThat(store.tryAcquire("recovered", Duration.ofMinutes(5)))
                .as("过期条目清理后应恢复接收")
                .isTrue();
    }

    @Test
    @Timeout(30)
    @DisplayName("并发压测不死锁：多线程高频 tryAcquire 在容量受限下可完成")
    void shouldNotDeadlockUnderConcurrentLoad() throws Exception {
        int threads = 8;
        int opsPerThread = 20_000;
        MemoryDedupeStore store = new MemoryDedupeStore(2_000);
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    String key = "k-" + random.nextInt(4_000);
                    store.tryAcquire(key, Duration.ofMillis(random.nextLong(1, 20)));
                    if (random.nextInt(10) == 0) {
                        store.release(key);
                    }
                }
                return null;
            }));
        }
        pool.shutdown();

        assertThat(pool.awaitTermination(25, TimeUnit.SECONDS))
                .as("compute 回调内不得对同一 map 做结构性修改，否则死锁")
                .isTrue();
        for (Future<?> future : futures) {
            future.get(1, TimeUnit.SECONDS);
        }
        assertThat(store.size()).isLessThanOrEqualTo(2_000 + threads);
    }
}
