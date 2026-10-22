package io.github.pkyit.notifyx.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * JDBC 失败落盘（设计书 §9.9 / §12.2 {@code nx_failure_record}）。
 *
 * <p>状态机与文件版一致：PENDING → SENDING → SENT/DEAD；{@code version} 做乐观锁，
 * 多实例扫同一张表时只有一个能抢到。</p>
 *
 * <p>@implNote SQL 只用标准语法 + {@code limit}（MySQL/H2/PostgreSQL 均支持），
 * 时间统一存 {@link Timestamp}；DDL 按库分目录。</p>
 *
 * @since 1.0
 */
public final class JdbcFailureStore implements FailureStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFailureStore.class);

    private static final String INSERT = """
            insert into nx_failure_record (record_id, envelope_json, channel, status, failure_reason,
                                           attempts, version, next_retry_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_PENDING = """
            select record_id, envelope_json, channel, status, failure_reason, attempts, version,
                   next_retry_at, created_at
            from nx_failure_record
            where status = 'PENDING' and next_retry_at <= ?
            order by next_retry_at
            limit ?
            """;

    private static final String UPDATE_STATUS = """
            update nx_failure_record
            set status = ?, attempts = ?, version = version + 1, updated_at = ?
            where record_id = ? and version = ?
            """;

    private static final String REQUEUE = """
            update nx_failure_record
            set status = 'PENDING', attempts = ?, next_retry_at = ?, version = version + 1, updated_at = ?
            where record_id = ? and version = ?
            """;

    private static final String COUNT_PENDING = "select count(*) from nx_failure_record where status = 'PENDING'";

    private final DataSource dataSource;

    /**
     * 构造失败存储。
     *
     * @param dataSource 数据源
     */
    public JdbcFailureStore(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void save(FailedRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, record.recordId());
            statement.setString(2, record.envelopeJson());
            statement.setString(3, record.channel());
            statement.setString(4, record.status().name());
            statement.setString(5, record.failureReason());
            statement.setInt(6, record.attempts());
            statement.setInt(7, record.version());
            statement.setTimestamp(8, new Timestamp(record.nextRetryAt()));
            statement.setTimestamp(9, new Timestamp(record.createdAt()));
            statement.setTimestamp(10, new Timestamp(System.currentTimeMillis()));
            statement.executeUpdate();
        } catch (SQLException ex) {
            log.warn("notifyx jdbc failure save failed: {}", ex.toString());
        }
    }

    @Override
    public List<FailedRecord> loadPending(int limit) {
        List<FailedRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_PENDING)) {
            statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            statement.setInt(2, Math.max(0, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(new FailedRecord(
                            resultSet.getString("record_id"),
                            RecordStatus.valueOf(resultSet.getString("status")),
                            resultSet.getInt("version"),
                            resultSet.getString("envelope_json"),
                            resultSet.getString("channel"),
                            resultSet.getString("failure_reason"),
                            resultSet.getInt("attempts"),
                            resultSet.getTimestamp("created_at").getTime(),
                            resultSet.getTimestamp("next_retry_at").getTime()));
                }
            }
        } catch (SQLException ex) {
            log.warn("notifyx jdbc failure load failed: {}", ex.toString());
        }
        return records;
    }

    @Override
    public boolean updateStatus(String recordId, int expectedVersion, RecordStatus status, int attempts) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
            statement.setString(1, status.name());
            statement.setInt(2, attempts);
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.setString(4, recordId);
            statement.setInt(5, expectedVersion);
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            log.warn("notifyx jdbc failure status update failed: {}", ex.toString());
            return false;
        }
    }

    @Override
    public boolean requeue(String recordId, int expectedVersion, int attempts, long nextRetryAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(REQUEUE)) {
            statement.setInt(1, attempts);
            statement.setTimestamp(2, new Timestamp(nextRetryAt));
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.setString(4, recordId);
            statement.setInt(5, expectedVersion);
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            log.warn("notifyx jdbc failure requeue failed: {}", ex.toString());
            return false;
        }
    }

    @Override
    public long pendingCount() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COUNT_PENDING);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (SQLException ex) {
            log.warn("notifyx jdbc failure count failed: {}", ex.toString());
            return 0L;
        }
    }
}
