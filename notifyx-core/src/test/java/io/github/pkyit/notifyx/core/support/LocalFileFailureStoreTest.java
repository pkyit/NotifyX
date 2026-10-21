package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * 本地文件失败存储测试（设计书 §9.9）：持久化、乐观锁、容量保护、损坏文件跳过。
 *
 * @since 1.0
 */
class LocalFileFailureStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写盘后重新打开仍能读到（进程重启不丢）")
    void shouldSurviveRestart() {
        LocalFileFailureStore first = store(100, 10_000_000L);
        first.save(record("evt_1", RecordStatus.PENDING, 0, 0L, "订单\"失败\"\n第二行"));

        LocalFileFailureStore second = store(100, 10_000_000L);

        assertThat(second.recordCount()).isEqualTo(1);
        assertThat(second.pendingCount()).isEqualTo(1);
        List<FailedRecord> pending = second.loadPending(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).failureReason()).isEqualTo("订单\"失败\"\n第二行");
        assertThat(pending.get(0).channel()).isEqualTo("email");
    }

    @Test
    @DisplayName("乐观锁：版本不匹配更新失败，匹配则推进版本与状态")
    void shouldApplyOptimisticLock() {
        LocalFileFailureStore store = store(100, 10_000_000L);
        store.save(record("evt_1", RecordStatus.PENDING, 0, 0L, "timeout"));

        assertThat(store.updateStatus("evt_1", 5, RecordStatus.SENDING, 1)).isFalse();
        assertThat(store.updateStatus("evt_1", 0, RecordStatus.SENDING, 1)).isTrue();
        assertThat(store.pendingCount()).isZero();

        // 版本已推进到 1，旧版本再更新失败
        assertThat(store.updateStatus("evt_1", 0, RecordStatus.SENT, 2)).isFalse();
        assertThat(store.updateStatus("evt_1", 1, RecordStatus.SENT, 2)).isTrue();
    }

    @Test
    @DisplayName("超出文件数上限时删除最旧记录并计数")
    void shouldEnforceCapacity() {
        LocalFileFailureStore store = store(2, 10_000_000L);
        store.save(record("evt_1", RecordStatus.PENDING, 0, 0L, "a"));
        store.save(record("evt_2", RecordStatus.PENDING, 0, 0L, "b"));
        store.save(record("evt_3", RecordStatus.PENDING, 0, 0L, "c"));

        assertThat(store.recordCount()).isEqualTo(2);
        assertThat(store.overflowCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("损坏的 JSON 文件被跳过而不是让整次扫描失败")
    void shouldSkipCorruptFiles() throws Exception {
        LocalFileFailureStore store = store(100, 10_000_000L);
        store.save(record("evt_1", RecordStatus.PENDING, 0, 0L, "ok"));
        Path hourDir = Files.list(tempDir).filter(Files::isDirectory).findFirst().orElseThrow();
        Files.writeString(hourDir.resolve("broken.json"), "{not-json", StandardCharsets.UTF_8);

        LocalFileFailureStore reopened = store(100, 10_000_000L);

        assertThat(reopened.recordCount()).isEqualTo(1);
        assertThat(reopened.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("loadPending 只返回到期 PENDING，并按 nextRetryAt 升序")
    void shouldLoadOnlyDuePending() {
        LocalFileFailureStore store = store(100, 10_000_000L);
        long now = System.currentTimeMillis();
        store.save(record("late", RecordStatus.PENDING, 0, now + 60_000, "late"));
        store.save(record("early", RecordStatus.PENDING, 0, now - 60_000, "early"));
        store.save(record("sent", RecordStatus.SENT, 0, now - 60_000, "sent"));

        List<FailedRecord> pending = store.loadPending(10);

        assertThat(pending).extracting(FailedRecord::recordId).containsExactly("early");
        assertThat(store.loadPending(0)).isEmpty();
    }

    @Test
    @DisplayName("未知记录更新返回 false")
    void shouldReturnFalseForUnknownRecord() {
        LocalFileFailureStore store = store(100, 10_000_000L);

        assertThat(store.updateStatus("missing", 0, RecordStatus.SENT, 1)).isFalse();
    }

    private LocalFileFailureStore store(int maxFiles, long maxBytes) {
        return new LocalFileFailureStore(tempDir, maxFiles, maxBytes);
    }

    private static FailedRecord record(String id, RecordStatus status, int version, long nextRetryAt, String reason) {
        return new FailedRecord(id, status, version, "{\"eventId\":\"" + id + "\"}", "email", reason, 1,
                System.currentTimeMillis(), nextRetryAt);
    }
}
