package io.github.pkyit.notifyx.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.spi.AuditRecord;

/**
 * 审计保留期测试：分批删除早于截止时间的记录，保留新记录。
 *
 * @since 1.0
 */
class JdbcAuditRetentionTest extends H2TestSupport {

    @Test
    @DisplayName("按截止时间分批删除：旧记录删除、新记录保留")
    void shouldPurgeOldRecords() throws Exception {
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(dataSource);
        long now = System.currentTimeMillis();
        long old = now - 3 * 86_400_000L;
        for (int i = 0; i < 3; i++) {
            recorder.record(record("old-" + i, old));
        }
        recorder.record(record("new-1", now));
        recorder.record(record("new-2", now));

        int deleted = new JdbcAuditRetention(dataSource, 2).purgeBefore(now - 86_400_000L);

        assertThat(deleted).isEqualTo(3);
        assertThat(count("nx_audit")).isEqualTo(2);
        assertThat(countOld(now - 86_400_000L)).isZero();
    }

    @Test
    @DisplayName("purgeOlderThan 按天计算，保留窗口内数据")
    void shouldPurgeByDays() throws Exception {
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(dataSource);
        long now = System.currentTimeMillis();
        recorder.record(record("old", now - 40 * 86_400_000L));
        recorder.record(record("recent", now));

        assertThat(new JdbcAuditRetention(dataSource).purgeOlderThan(30)).isEqualTo(1);
        assertThat(count("nx_audit")).isEqualTo(1);
    }

    private AuditRecord record(String eventId, long createdAt) {
        return new AuditRecord(eventId, "SCENE_A", "ERROR", "B1", "log", null, "SUCCESS", null,
                1, 0L, "inst", null, createdAt, createdAt);
    }

    private long countOld(long cutoff) throws Exception {
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from nx_audit where created_at < ?")) {
            statement.setTimestamp(1, new java.sql.Timestamp(cutoff));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
