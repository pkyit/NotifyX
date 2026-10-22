package io.github.pkyit.notifyx.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * 真实数据库集成测试：设置 {@code NOTIFYX_JDBC_URL/USER/PASS} 后执行。
 *
 * <pre>
 * NOTIFYX_JDBC_URL=jdbc:mysql://10.10.10.102:3306/notifyx_test?useSSL=false
 * NOTIFYX_JDBC_USER=root NOTIFYX_JDBC_PASS=*** NOTIFYX_JDBC_DIALECT=mysql
 * mvn -B -pl notifyx-store-jdbc test -Dtest=JdbcStoresLiveTest
 * </pre>
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_JDBC_URL", matches = ".+")
class JdbcStoresLiveTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        String dialect = System.getenv().getOrDefault("NOTIFYX_JDBC_DIALECT", "mysql");
        dataSource = new SimpleDataSource(System.getenv("NOTIFYX_JDBC_URL"),
                System.getenv("NOTIFYX_JDBC_USER"), System.getenv("NOTIFYX_JDBC_PASS"));
        String schema = new String(getClass().getResourceAsStream("/sql/" + dialect + "/schema.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists nx_audit");
            statement.execute("drop table if exists nx_failure_record");
            for (String sql : schema.split(";")) {
                String cleaned = java.util.Arrays.stream(sql.split("\n"))
                        .filter(line -> !line.trim().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .trim();
                if (!cleaned.isEmpty()) {
                    statement.execute(cleaned);
                }
            }
        }
    }

    @Test
    @DisplayName("真实库：审计写入可查询")
    void auditShouldPersist() throws Exception {
        new JdbcAuditRecorder(dataSource).record(new AuditRecord("evt_live", "SCENE_A", "ERROR", "B1",
                "email", "p***@qq.com", "SUCCESS", null, 1, 5L, "inst", "trace",
                System.currentTimeMillis(), System.currentTimeMillis()));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from nx_audit")) {
            resultSet.next();
            assertThat(resultSet.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("真实库：失败记录保存、CAS、重排")
    void failureStoreShouldWork() {
        JdbcFailureStore store = new JdbcFailureStore(dataSource);
        long now = System.currentTimeMillis();
        store.save(new FailedRecord("r1", RecordStatus.PENDING, 0, "{\"eventId\":\"r1\"}", "email",
                "timeout", 0, now, now - 1000));

        List<FailedRecord> pending = store.loadPending(10);
        assertThat(pending).hasSize(1);
        assertThat(store.updateStatus("r1", 0, RecordStatus.SENDING, 1)).isTrue();
        assertThat(store.updateStatus("r1", 0, RecordStatus.SENT, 1)).isFalse();
        assertThat(store.requeue("r1", 1, 1, now - 1000)).isTrue();
        assertThat(store.pendingCount()).isEqualTo(1);
    }

    private static final class SimpleDataSource implements DataSource {

        private final String url;
        private final String user;
        private final String password;

        private SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            return DriverManager.getConnection(url, username, pwd);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
