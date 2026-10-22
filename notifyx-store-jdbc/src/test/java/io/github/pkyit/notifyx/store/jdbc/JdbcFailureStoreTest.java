package io.github.pkyit.notifyx.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * JDBC 失败补偿存储测试：保存、到期读取、乐观锁、重排、计数。
 *
 * @since 1.0
 */
class JdbcFailureStoreTest extends H2TestSupport {

    private JdbcFailureStore store() {
        return new JdbcFailureStore(dataSource);
    }

    @Test
    @DisplayName("save + loadPending：只取 PENDING 且到期的记录")
    void shouldLoadPendingOnly() {
        JdbcFailureStore store = store();
        long now = System.currentTimeMillis();
        store.save(record("r1", RecordStatus.PENDING, 0, now - 1000));
        store.save(record("r2", RecordStatus.PENDING, 0, now + 60_000));
        store.save(record("r3", RecordStatus.SENT, 0, now - 1000));

        List<FailedRecord> pending = store.loadPending(10);

        assertThat(pending).extracting(FailedRecord::recordId).containsExactly("r1");
        assertThat(pending.get(0).envelopeJson()).isEqualTo("{\"eventId\":\"r1\"}");
        assertThat(store.pendingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("updateStatus：版本匹配才成功，成功后 version+1")
    void shouldUseOptimisticLock() {
        JdbcFailureStore store = store();
        store.save(record("r1", RecordStatus.PENDING, 0, 0));

        assertThat(store.updateStatus("r1", 0, RecordStatus.SENDING, 1)).isTrue();
        assertThat(store.updateStatus("r1", 0, RecordStatus.SENT, 1)).isFalse();
        assertThat(store.updateStatus("r1", 1, RecordStatus.SENT, 1)).isTrue();
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    @DisplayName("requeue：回到 PENDING 并更新 nextRetryAt/attempts/version")
    void shouldRequeue() {
        JdbcFailureStore store = store();
        long now = System.currentTimeMillis();
        store.save(record("r1", RecordStatus.PENDING, 0, now - 1000));
        assertThat(store.updateStatus("r1", 0, RecordStatus.SENDING, 1)).isTrue();

        long next = now - 1000;
        assertThat(store.requeue("r1", 1, 1, next)).isTrue();

        List<FailedRecord> pending = store.loadPending(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo(RecordStatus.PENDING);
        assertThat(pending.get(0).attempts()).isEqualTo(1);
        assertThat(pending.get(0).version()).isEqualTo(2);
        assertThat(pending.get(0).nextRetryAt()).isEqualTo(next);
    }

    private static FailedRecord record(String id, RecordStatus status, int version, long nextRetryAt) {
        return new FailedRecord(id, status, version, "{\"eventId\":\"" + id + "\"}", "email", "timeout", 0,
                System.currentTimeMillis(), nextRetryAt);
    }
}
