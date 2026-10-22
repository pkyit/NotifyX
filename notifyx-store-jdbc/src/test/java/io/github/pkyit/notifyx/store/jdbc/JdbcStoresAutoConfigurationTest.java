package io.github.pkyit.notifyx.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.FailureStore;

/**
 * JDBC 存储自动装配测试：有 DataSource + 对应 store 开关才生效，并优先于默认实现。
 *
 * @since 1.0
 */
class JdbcStoresAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcStoresAutoConfiguration.class))
            .withBean(DataSource.class, JdbcStoresAutoConfigurationTest::h2);

    @Test
    @DisplayName("audit.store=jdbc / fallback.store=jdbc 时装配 JDBC 实现")
    void shouldCreateJdbcBeans() {
        runner.withPropertyValues("notifyx.audit.store=jdbc", "notifyx.fallback.store=jdbc")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditRecorder.class);
                    assertThat(context).hasSingleBean(FailureStore.class);
                    assertThat(context.getBean(AuditRecorder.class)).isInstanceOf(JdbcAuditRecorder.class);
                    assertThat(context.getBean(FailureStore.class)).isInstanceOf(JdbcFailureStore.class);
                });
    }

    @Test
    @DisplayName("未开启 store=jdbc 时不装配")
    void shouldBackOffByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AuditRecorder.class);
            assertThat(context).doesNotHaveBean(FailureStore.class);
        });
    }

    @Test
    @DisplayName("与 NotifyxAutoConfiguration 同时存在时，JDBC 实现优先")
    void jdbcShouldWinOverDefaults() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcStoresAutoConfiguration.class,
                        NotifyxAutoConfiguration.class))
                .withBean(DataSource.class, JdbcStoresAutoConfigurationTest::h2)
                .withPropertyValues("notifyx.audit.store=jdbc", "notifyx.fallback.store=jdbc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuditRecorder.class)).isInstanceOf(JdbcAuditRecorder.class);
                    assertThat(context.getBean(FailureStore.class)).isInstanceOf(JdbcFailureStore.class);
                });
    }

    private static DataSource h2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:autoconfig-" + System.nanoTime());
        h2.setUser("sa");
        h2.setPassword("");
        return h2;
    }
}
