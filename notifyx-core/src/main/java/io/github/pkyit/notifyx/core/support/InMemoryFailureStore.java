package io.github.pkyit.notifyx.core.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * 有界内存失败存储：用于 P0/P1 的默认兜底，进程重启后丢失。
 *
 * <p>生产环境请替换为 local-file 或 JDBC 实现（Roadmap）。</p>
 *
 * <p>@implNote 线程安全；容量上限防止内存被写入打爆。</p>
 *
 * @since 1.0
 */
public final class InMemoryFailureStore implements FailureStore {

    /** 默认容量。 */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final Deque<FailedRecord> records = new ArrayDeque<>();

    /**
     * 使用默认容量构造。
     */
    public InMemoryFailureStore() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 构造存储。
     *
     * @param capacity 容量上限
     */
    public InMemoryFailureStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized void save(FailedRecord record) {
        if (records.size() >= capacity) {
            records.pollFirst();
        }
        records.addLast(record);
    }

    @Override
    public synchronized List<FailedRecord> loadPending(int limit) {
        long now = System.currentTimeMillis();
        return records.stream()
                .filter(record -> record.status() == RecordStatus.PENDING && record.nextRetryAt() <= now)
                .sorted(Comparator.comparingLong(FailedRecord::nextRetryAt))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public synchronized boolean updateStatus(String recordId, int expectedVersion, RecordStatus status, int attempts) {
        Optional<FailedRecord> found = records.stream()
                .filter(record -> record.recordId().equals(recordId))
                .findFirst();
        if (found.isEmpty() || found.get().version() != expectedVersion) {
            return false;
        }
        FailedRecord current = found.get();
        List<FailedRecord> snapshot = new ArrayList<>(records);
        snapshot.replaceAll(record -> record.recordId().equals(recordId)
                ? new FailedRecord(current.recordId(), status, current.version() + 1, current.envelopeJson(),
                        current.channel(), current.failureReason(), attempts, current.createdAt(), current.nextRetryAt())
                : record);
        records.clear();
        records.addAll(snapshot);
        return true;
    }

    @Override
    public synchronized long pendingCount() {
        return records.stream().filter(record -> record.status() == RecordStatus.PENDING).count();
    }
}