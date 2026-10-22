package io.github.pkyit.notifyx.consumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC 幂等（T1，设计书 §11.4）：`nx_idempotency` 唯一键，插入冲突视为重复。
 *
 * <p>非唯一约束类 SQL 异常采用 fail-open（放行并告警），避免幂等存储故障导致所有通知被丢。</p>
 *
 * @since 1.0
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotencyStore.class);

    private static final String INSERT = """
            insert into nx_idempotency (idempotency_key, event_id, created_at)
            values (?, ?, ?)
            """;

    private final DataSource dataSource;

    /**
     * 构造存储。
     *
     * @param dataSource 数据源
     */
    public JdbcIdempotencyStore(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean firstSeen(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, key);
            statement.setString(2, key);
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.executeUpdate();
            return true;
        } catch (SQLException ex) {
            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                return false;
            }
            log.warn("notifyx jdbc idempotency failed, fail-open: {}", ex.toString());
            return true;
        }
    }
}
