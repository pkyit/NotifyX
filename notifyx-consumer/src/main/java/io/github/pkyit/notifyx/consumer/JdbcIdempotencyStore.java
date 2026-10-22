package io.github.pkyit.notifyx.consumer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC 幂等（T1，设计书 §11.4）：`nx_idempotency` 唯一键，插入冲突视为重复。
 *
 * <p>直接用 {@link DriverManager}（避免引入 Spring DataSource 自动配置，防止未配置数据库时
 * consumer 启动失败）；驱动由部署方按需提供。</p>
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

    private final String url;
    private final String user;
    private final String password;

    /**
     * 构造存储。
     *
     * @param url JDBC URL
     * @param user 用户名
     * @param password 密码
     */
    public JdbcIdempotencyStore(String url, String user, String password) {
        this.url = java.util.Objects.requireNonNull(url, "url");
        this.user = user;
        this.password = password;
    }

    @Override
    public boolean firstSeen(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
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
