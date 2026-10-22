package io.github.pkyit.notifyx.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.spi.AuditRecord;

/**
 * JDBC 审计写入测试。
 *
 * @since 1.0
 */
class JdbcAuditRecorderTest extends H2TestSupport {

    @Test
    @DisplayName("写入 nx_audit：脱敏接收人、状态、耗时、sent_at")
    void shouldInsertAudit() throws Exception {
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(dataSource);

        recorder.record(new AuditRecord("evt_1", "ORDER_CREATE_FAIL", "ERROR", "SO1", "email",
                "p***@qq.com", "SUCCESS", null, 1, 12L, "inst-1", "trace-1",
                System.currentTimeMillis(), System.currentTimeMillis()));

        assertThat(count("nx_audit")).isEqualTo(1);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select scene, status, receiver, sent_at from nx_audit")) {
            resultSet.next();
            assertThat(resultSet.getString("scene")).isEqualTo("ORDER_CREATE_FAIL");
            assertThat(resultSet.getString("status")).isEqualTo("SUCCESS");
            assertThat(resultSet.getString("receiver")).isEqualTo("p***@qq.com");
            assertThat(resultSet.getTimestamp("sent_at")).isNotNull();
        }
    }

    @Test
    @DisplayName("sent_at 为空时允许写入")
    void shouldAllowNullSentAt() throws Exception {
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(dataSource);

        recorder.record(new AuditRecord("evt_2", "S", "WARN", null, "log", null, "FAIL_RETRYABLE",
                "timeout", 1, 0L, null, null, System.currentTimeMillis(), null));

        assertThat(count("nx_audit")).isEqualTo(1);
    }
}
