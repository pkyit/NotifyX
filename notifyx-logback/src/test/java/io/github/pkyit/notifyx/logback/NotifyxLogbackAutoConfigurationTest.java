package io.github.pkyit.notifyx.logback;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.api.NotifyKit;

/**
 * 自动装配测试：只在「有 NotifyKit」时装配绑定器，并且 imports 文件可被 Spring Boot 发现。
 *
 * @since 1.0
 */
class NotifyxLogbackAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxLogbackAutoConfiguration.class));

    @Test
    @DisplayName("有 NotifyKit 时装配绑定器")
    void shouldBindWhenNotifyKitPresent() {
        runner.withBean(NotifyKit.class, RecordingNotifyKit::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(NotifyxAppenderBinder.class);
                    assertThat(context).hasSingleBean(NotifyKit.class);
                });
    }

    @Test
    @DisplayName("没有 NotifyKit 时不装配（零依赖可启动）")
    void shouldBackOffWithoutNotifyKit() {
        runner.run(context -> assertThat(context).doesNotHaveBean(NotifyxAppenderBinder.class));
    }

    @Test
    @DisplayName("notifyx.logback.enabled=false 时关闭")
    void shouldBackOffWhenDisabled() {
        runner.withPropertyValues("notifyx.logback.enabled=false")
                .withBean(NotifyKit.class, RecordingNotifyKit::new)
                .run(context -> assertThat(context).doesNotHaveBean(NotifyxAppenderBinder.class));
    }

    @Test
    @DisplayName("AutoConfiguration.imports 里登记了本模块的自动配置")
    void shouldBeRegisteredInImportsFile() throws Exception {
        try (var in = getClass().getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains(NotifyxLogbackAutoConfiguration.class.getName());
        }
    }
}
