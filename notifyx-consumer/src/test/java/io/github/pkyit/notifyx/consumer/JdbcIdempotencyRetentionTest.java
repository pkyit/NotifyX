package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code nx_idempotency} 保留期清理测试：分批删除早于截止时间的记录，保留新记录（H2）。
 *
 * @since 1.0
 */
class JdbcIdempotencyRetentionTest {

    private String url;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:idem-ret-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table nx_idempotency (idempotency_key varchar(128) primary key, "
                    + "event_id varchar(64) not null, created_at timestamp not null)");
            statement.execute("create index idx_nx_idem_created on nx_idempotency (created_at)");
        }
    }

    @Test
    @DisplayName("按截止时间分批删除：旧记录删除、新记录保留")
    void shouldPurgeOldRecords() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 3 * 86_400_000L;
        insert("old-1", old);
        insert("old-2", old);
        insert("old-3", old);
        insert("new-1", now);
        insert("new-2", now);

        int deleted = new JdbcIdempotencyRetention(url, "sa", "", 2).purgeBefore(now - 86_400_000L);

        assertThat(deleted).isEqualTo(3);
        assertThat(count()).isEqualTo(2);
        assertThat(countOld(now - 86_400_000L)).isZero();
    }

    @Test
    @DisplayName("purgeOlderThan 按天计算，保留窗口内数据")
    void shouldPurgeByDays() throws Exception {
        long now = System.currentTimeMillis();
        insert("old", now - 40 * 86_400_000L);
        insert("recent", now);

        assertThat(new JdbcIdempotencyRetention(url, "sa", "").purgeOlderThan(30)).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    private void insert(String key, long createdAt) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "insert into nx_idempotency (idempotency_key, event_id, created_at) values (?, ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, key);
            statement.setTimestamp(3, new Timestamp(createdAt));
            statement.executeUpdate();
        }
    }

    private long count() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from nx_idempotency")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long countOld(long cutoff) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from nx_idempotency where created_at < ?")) {
            statement.setTimestamp(1, new Timestamp(cutoff));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
