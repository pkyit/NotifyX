package io.github.pkyit.notifyx.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;

/**
 * JDBC 审计：写入 {@code nx_audit}（设计书 §12.2）。
 *
 * <p>审计是观测旁路，任何 SQL 异常只 warn，不影响通知发送。</p>
 *
 * <p>@implNote 线程安全；每次调用取连接并关闭。主键用随机 63 位正数，
 * 避免依赖各库自增/序列语法（信创库差异大）。</p>
 *
 * @since 1.0
 */
public final class JdbcAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditRecorder.class);

    private static final String INSERT = """
            insert into nx_audit (id, event_id, scene, level, biz_id, channel, receiver, status, error_msg,
                                  attempts, cost_ms, instance_id, trace_id, app_id, tenant, created_at, sent_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;

    /**
     * 构造审计器。
     *
     * @param dataSource 数据源
     */
    public JdbcAuditRecorder(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void record(AuditRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setLong(1, nextId());
            statement.setString(2, record.eventId());
            statement.setString(3, record.scene());
            statement.setString(4, record.level());
            statement.setString(5, record.bizId());
            statement.setString(6, record.channel());
            statement.setString(7, record.receiver());
            statement.setString(8, record.status());
            statement.setString(9, record.errorMsg());
            statement.setInt(10, record.attempts());
            statement.setLong(11, record.costMs());
            statement.setString(12, record.instanceId());
            statement.setString(13, record.traceId());
            statement.setString(14, null);
            statement.setString(15, null);
            statement.setTimestamp(16, new Timestamp(record.createdAt()));
            statement.setTimestamp(17, record.sentAt() == null ? null : new Timestamp(record.sentAt()));
            statement.executeUpdate();
        } catch (SQLException ex) {
            log.warn("notifyx jdbc audit insert failed: {}", ex.toString());
        }
    }

    private static long nextId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
