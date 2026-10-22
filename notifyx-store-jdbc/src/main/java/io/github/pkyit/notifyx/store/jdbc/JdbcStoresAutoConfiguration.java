package io.github.pkyit.notifyx.store.jdbc;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.FailureStore;

/**
 * JDBC 存储自动装配：审计（{@code notifyx.audit.store=jdbc}）与失败补偿
 * （{@code notifyx.fallback.store=jdbc}）。
 *
 * <p>必须在 {@code NotifyxAutoConfiguration} 之前，让后者的
 * {@code @ConditionalOnMissingBean} 让位；没有 DataSource 时不装配。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class,
        beforeName = "io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration")
@ConditionalOnClass(DataSource.class)
@ConditionalOnBean(DataSource.class)
public class JdbcStoresAutoConfiguration {

    /**
     * JDBC 审计。
     *
     * @param dataSource 数据源
     * @return 审计器
     */
    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    @ConditionalOnProperty(prefix = "notifyx.audit", name = "store", havingValue = "jdbc")
    public JdbcAuditRecorder jdbcAuditRecorder(DataSource dataSource) {
        return new JdbcAuditRecorder(dataSource);
    }

    /**
     * JDBC 失败落盘。
     *
     * @param dataSource 数据源
     * @return 失败存储
     */
    @Bean
    @ConditionalOnMissingBean(FailureStore.class)
    @ConditionalOnProperty(prefix = "notifyx.fallback", name = "store", havingValue = "jdbc")
    public JdbcFailureStore jdbcFailureStore(DataSource dataSource) {
        return new JdbcFailureStore(dataSource);
    }
}
