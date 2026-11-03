package io.github.pkyit.notifyx.consumer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code nx_idempotency} 保留期清理（设计书 §11.4 / §12.3）。
 *
 * <p>与 {@link JdbcIdempotencyStore} 一致使用 {@link DriverManager} 直连，避免 consumer 引入
 * Spring DataSource 自动配置（防止未配置数据库时启动失败）。循环「查一批 key → 按 key IN 删除」，
 * 每批 {@code batchSize} 行，避免大事务锁表。</p>
 *
 * <p>幂等记录默认保留 24h（与 {@code notifyx.consumer.idempotency-ttl} 对齐），过期后清理可释放磁盘；
 * 保留期是数据治理策略，调度由部署方负责（Spring {@code @Scheduled}、k8s CronJob 等），
 * SDK 不内置后台线程、不默认自动删除。</p>
 *
 * <p>@implNote SQL 使用标准 {@code limit} 与 {@code in}，MySQL/H2/PostgreSQL 均适用；
 * DM/Kingbase/openGauss 的 DDL 见 {@code sql/{dm,kingbase,opengauss}/idempotency.sql}。</p>
 *
 * @since 1.0
 */
public final class JdbcIdempotencyRetention {

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotencyRetention.class);
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private static final String SELECT_KEYS = """
            select idempotency_key from nx_idempotency
            where created_at < ?
            order by created_at
            limit ?
            """;

    private final String url;
    private final String user;
    private final String password;
    private final int batchSize;

    /**
     * 构造（默认批大小 1000）。
     *
     * @param url JDBC URL
     * @param user 用户名
     * @param password 密码
     */
    public JdbcIdempotencyRetention(String url, String user, String password) {
        this(url, user, password, DEFAULT_BATCH_SIZE);
    }

    /**
     * 构造。
     *
     * @param url JDBC URL
     * @param user 用户名
     * @param password 密码
     * @param batchSize 批大小
     */
    public JdbcIdempotencyRetention(String url, String user, String password, int batchSize) {
        this.url = java.util.Objects.requireNonNull(url, "url");
        this.user = user;
        this.password = password;
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 删除早于给定时间点的幂等记录。
     *
     * @param cutoffMillis 截止时间（epoch millis，删除严格早于它的记录）
     * @return 删除行数
     */
    public int purgeBefore(long cutoffMillis) {
        int total = 0;
        while (true) {
            List<String> keys = selectBatch(cutoffMillis);
            if (keys.isEmpty()) {
                return total;
            }
            int deleted = deleteBatch(keys);
            total += deleted;
            if (deleted == 0) {
                log.warn("notifyx jdbc idempotency retention delete made no progress, stop this round: selected={}",
                        keys.size());
                return total;
            }
            if (keys.size() < batchSize) {
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

    private List<String> selectBatch(long cutoffMillis) {
        List<String> keys = new ArrayList<>(batchSize);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(SELECT_KEYS)) {
            statement.setTimestamp(1, new Timestamp(cutoffMillis));
            statement.setInt(2, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    keys.add(resultSet.getString(1));
                }
            }
        } catch (SQLException ex) {
            log.warn("notifyx jdbc idempotency retention select failed: {}", ex.toString());
        }
        return keys;
    }

    private int deleteBatch(List<String> keys) {
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        String sql = "delete from nx_idempotency where idempotency_key in (" + placeholders + ")";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < keys.size(); i++) {
                statement.setString(i + 1, keys.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException ex) {
            log.warn("notifyx jdbc idempotency retention delete failed: {}", ex.toString());
            return 0;
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
