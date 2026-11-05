package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.pipeline.SceneRegistry;

/**
 * 总开关（notifyx.enabled）装配契约测试。
 *
 * <p>锁定三条契约：</p>
 * <ol>
 *   <li>关闭态仍能注入 {@link NotifyKit}（Null Object），不破坏业务方构造器强依赖；</li>
 *   <li>关闭态不装配管线（{@code DefaultNotifyKit}），所有通知静默丢弃；</li>
 *   <li>关闭态下其余 SPI 组件（执行器、场景注册表）不受影响。</li>
 * </ol>
 *
 * @since 1.0.4
 */
class NotifyxDisabledWiringTest {

    @Test
    @DisplayName("总开关关闭：NotifyKit 仍可注入，且为空转实现")
    void shouldProvideNoopKitWhenDisabled() {
        runner(false).run(context -> {
            NotifyKit kit = context.getBean(NotifyKit.class);
            assertThat(kit).isNotNull();
            assertThat(kit).isInstanceOf(NotifyxDisabledAutoConfiguration.NoopNotifyKit.class);
        });
    }

    @Test
    @DisplayName("总开关关闭：send 不产生任何副作用（无管线、无异常）")
    void shouldSilentlyDropWhenDisabled() {
        runner(false).run(context -> {
            NotifyKit kit = context.getBean(NotifyKit.class);
            // 空转实现：调用不抛异常即可
            kit.send(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());
            kit.alarm("SCENE_A", new RuntimeException("boom"), "k", "v");
        });
    }

    @Test
    @DisplayName("总开关开启（缺省）：NotifyKit 为真实管线实现")
    void shouldProvideRealKitWhenEnabled() {
        runner(true).run(context -> {
            NotifyKit kit = context.getBean(NotifyKit.class);
            assertThat(kit).isNotInstanceOf(NotifyxDisabledAutoConfiguration.NoopNotifyKit.class);
            assertThat(kit).isInstanceOf(io.github.pkyit.notifyx.core.pipeline.DefaultNotifyKit.class);
        });
    }

    @Test
    @DisplayName("总开关关闭：SPI 组件（执行器/场景注册表）仍装配，供业务方安全引用")
    void shouldKeepSpiBeansWhenDisabled() {
        runner(false).run(context -> {
            assertThat(context.getBeanNamesForType(NotifyTaskExecutor.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(SceneRegistry.class)).isNotEmpty();
        });
    }

    private static ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class,
                        NotifyxDisabledAutoConfiguration.class))
                .withPropertyValues(
                        "notifyx.async.enabled=false",
                        "notifyx.enabled=" + enabled);
    }
}
