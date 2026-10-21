package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * 有界内存失败存储测试（设计书 9.9）：容量淘汰、待补偿排序、乐观锁版本。
 *
 * @since 1.0
 */
class InMemoryFailureStoreTest {

    private static FailedRecord record(String id, RecordStatus status, int version, long nextRetryAt) {
        return new FailedRecord(id, status, version, "{}", "log", "boom", 0, System.currentTimeMillis(), nextRetryAt);
    }

    @Test
    @DisplayName("超过容量时淘汰最旧记录")
    void shouldEvictOldestOnCapacity() {
        InMemoryFailureStore store = new InMemoryFailureStore(2);
        long now = System.currentTimeMillis();

        store.save(record("r1", RecordStatus.PENDING, 0, now - 3));
        store.save(record("r2", RecordStatus.PENDING, 0, now - 2));
        store.save(record("r3", RecordStatus.PENDING, 0, now - 1));

        assertThat(store.loadPending(10)).extracting(FailedRecord::recordId).containsExactly("r2", "r3");
    }

    @Test
    @DisplayName("只返回到期的 PENDING 记录，并按 nextRetryAt 升序")
    void shouldLoadOnlyDuePendingRecords() {
        InMemoryFailureStore store = new InMemoryFailureStore(10);
        long now = System.currentTimeMillis();

        store.save(record("late", RecordStatus.PENDING, 0, now + 60_000));
        store.save(record("early", RecordStatus.PENDING, 0, now - 60_000));
        store.save(record("sent", RecordStatus.SENT, 0, now - 60_000));
        store.save(record("dead", RecordStatus.DEAD, 0, now - 60_000));

        assertThat(store.loadPending(10)).extracting(FailedRecord::recordId).containsExactly("early");
        assertThat(store.loadPending(0)).isEmpty();
        assertThat(store.pendingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("乐观锁：版本不匹配的更新被拒绝，匹配则推进版本与状态")
    void shouldUpdateWithOptimisticLock() {
        InMemoryFailureStore store = new InMemoryFailureStore(10);
        long now = System.currentTimeMillis();
        store.save(record("r1", RecordStatus.PENDING, 0, now - 1));

        assertThat(store.updateStatus("r1", 99, RecordStatus.SENDING, 1)).isFalse();
        assertThat(store.updateStatus("missing", 0, RecordStatus.SENDING, 1)).isFalse();

        assertThat(store.updateStatus("r1", 0, RecordStatus.SENT, 1)).isTrue();
        assertThat(store.loadPending(10)).isEmpty();
        assertThat(store.pendingCount()).isZero();

        // 版本已变为 1，旧版本再更新会被拒绝
        assertThat(store.updateStatus("r1", 0, RecordStatus.SENT, 2)).isFalse();
    }

    @Test
    @DisplayName("FailedRecord.nextAttempt 推进版本并保持 PENDING")
    void shouldComputeNextAttempt() {
        FailedRecord record = record("r1", RecordStatus.DEAD, 2, 0L);
        FailedRecord next = record.nextAttempt(3, 123L);

        assertThat(next.status()).isEqualTo(RecordStatus.PENDING);
        assertThat(next.version()).isEqualTo(3);
        assertThat(next.attempts()).isEqualTo(3);
        assertThat(next.nextRetryAt()).isEqualTo(123L);
    }

    @Test
    @DisplayName("默认容量是 10000，容量下限为 1")
    void shouldUseDefaultCapacity() {
        assertThat(InMemoryFailureStore.DEFAULT_CAPACITY).isEqualTo(10_000);
        InMemoryFailureStore tiny = new InMemoryFailureStore(0);
        tiny.save(record("a", RecordStatus.PENDING, 0, 0L));
        tiny.save(record("b", RecordStatus.PENDING, 0, 0L));
        assertThat(tiny.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("loadPending 的 limit 上限生效")
    void shouldLimitResults() {
        InMemoryFailureStore store = new InMemoryFailureStore(10);
        for (int i = 0; i < 5; i++) {
            store.save(record("r" + i, RecordStatus.PENDING, 0, 0L));
        }
        assertThat(store.loadPending(2)).hasSize(2);
    }
}
