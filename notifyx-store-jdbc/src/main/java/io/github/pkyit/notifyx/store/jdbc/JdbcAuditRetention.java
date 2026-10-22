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

/**
 * 审计容量治理（设计书 §12.3）：按 `created_at` 分批删除过期记录。
 *
 * <p>循环「查一批 id → 按 id 删除」，每批 {@code batchSize} 行，避免大事务锁表；
 * 调度由部署方负责（Spring {@code @Scheduled}、k8s CronJob 等），SDK 不内置后台线程。</p>
 *
 * <p>SQL 使用标准 {@code limit} 与 {@code in}，MySQL/H2/PostgreSQL 均适用；DM/Kingbase 待适配。</p>
 *
 * @since 1.0
 */
public final class JdbcAuditRetention {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditRetention.class);
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private static final String SELECT_IDS = """
            select id from nx_audit
            where created_at < ?
            order by created_at
            limit ?
            """;

    private final DataSource dataSource;
    private final int batchSize;

    /**
     * 构造（默认批大小 1000）。
     *
     * @param dataSource 数据源
     */
    public JdbcAuditRetention(DataSource dataSource) {
        this(dataSource, DEFAULT_BATCH_SIZE);
    }

    /**
     * 构造。
     *
     * @param dataSource 数据源
     * @param batchSize 批大小
     */
    public JdbcAuditRetention(DataSource dataSource, int batchSize) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 删除早于给定时间点的审计记录。
     *
     * @param cutoffMillis 截止时间（epoch millis，删除严格早于它的记录）
     * @return 删除行数
     */
    public int purgeBefore(long cutoffMillis) {
        int total = 0;
        while (true) {
            List<Long> ids = selectBatch(cutoffMillis);
            if (ids.isEmpty()) {
                return total;
            }
            total += deleteBatch(ids);
            if (ids.size() < batchSize) {
                return total;
            }
        }
    }

    /**
     * 按保留天数删除。
     *
     * @param retentionDays 保留天数，小于 1 时按 1 处理
     * @return 删除行数
     */
    public int purgeOlderThan(int retentionDays) {
        int days = Math.max(1, retentionDays);
        return purgeBefore(System.currentTimeMillis() - days * 86_400_000L);
    }

    private List<Long> selectBatch(long cutoffMillis) {
        List<Long> ids = new ArrayList<>(batchSize);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_IDS)) {
            statement.setTimestamp(1, new Timestamp(cutoffMillis));
            statement.setInt(2, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong(1));
                }
            }
        } catch (SQLException ex) {
            log.warn("notifyx jdbc audit retention select failed: {}", ex.toString());
        }
        return ids;
    }

    private int deleteBatch(List<Long> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "delete from nx_audit where id in (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setLong(i + 1, ids.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException ex) {
            log.warn("notifyx jdbc audit retention delete failed: {}", ex.toString());
            return 0;
        }
    }
}
