package io.github.pkyit.notifyx.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JDBC 幂等测试：唯一键冲突即重复。
 *
 * @since 1.0
 */
class JdbcIdempotencyStoreTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:idem-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        dataSource = h2;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table nx_idempotency (idempotency_key varchar(128) primary key, "
                    + "event_id varchar(64) not null, created_at timestamp not null)");
        }
    }

    @Test
    @DisplayName("首次插入 true，重复键 false")
    void shouldDetectDuplicateByUniqueKey() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource);

        assertThat(store.firstSeen("idem-jdbc-1")).isTrue();
        assertThat(store.firstSeen("idem-jdbc-1")).isFalse();
        assertThat(store.firstSeen("idem-jdbc-2")).isTrue();
    }

    @Test
    @DisplayName("空键不落库，直接放行")
    void blankKeyShouldPass() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource);

        assertThat(store.firstSeen("")).isTrue();
        assertThat(store.firstSeen("")).isTrue();
    }
}
