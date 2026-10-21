package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.channel.log.LogChannelAutoConfiguration;
import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * T13：零依赖启动测试——classpath 只有 core/spring/autoconfigure + log 渠道时，
 * 上下文必须启动成功，且所有 SPI 走内存兜底（承诺 P1）。
 *
 * @since 1.0
 */
class ZeroDependencyStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class, LogChannelAutoConfiguration.class));

    @Test
    @DisplayName("T13：无 Redis / MQ / DB 也能装配出 NotifyKit")
    void shouldStartWithoutMiddleware() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(NotifyKit.class);
            assertThat(context).hasSingleBean(DedupeStore.class);
            assertThat(context).hasSingleBean(FailureStore.class);
            assertThat(context).hasSingleBean(NotifyTaskExecutor.class);
            assertThat(context).hasSingleBean(NotifyMetrics.class);
            assertThat(context.getBeansOfType(NotifyChannel.class)).containsKey("notifyxLogChannel");
        });
    }

    @Test
    @DisplayName("notifyx.enabled=false 时不装配")
    void shouldBackOffWhenDisabled() {
        runner.withPropertyValues("notifyx.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NotifyKit.class));
    }

    @Test
    @DisplayName("同步模式（async.enabled=false）直接执行")
    void shouldSupportSyncMode() {
        runner.withPropertyValues("notifyx.async.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(NotifyTaskExecutor.class));
    }
}