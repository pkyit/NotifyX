package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.channel.log.LogChannelAutoConfiguration;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.support.InMemoryFailureStore;
import io.github.pkyit.notifyx.core.support.LocalFileFailureStore;

/**
 * 失败存储选型测试（设计书 9.9）：默认内存，配置 file 后落盘。
 *
 * @since 1.0
 */
class FailureStoreSelectionTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class, LogChannelAutoConfiguration.class));

    @Test
    @DisplayName("默认使用有界内存实现")
    void shouldUseMemoryByDefault() {
        runner.run(context -> assertThat(context.getBean(FailureStore.class))
                .isInstanceOf(InMemoryFailureStore.class));
    }

    @Test
    @DisplayName("notifyx.fallback.store=file 时落盘到指定目录")
    void shouldUseFileStoreWhenConfigured() {
        runner.withPropertyValues("notifyx.fallback.store=file",
                        "notifyx.fallback.dir=" + tempDir,
                        "notifyx.fallback.max-files=100")
                .run(context -> {
                    FailureStore store = context.getBean(FailureStore.class);
                    assertThat(store).isInstanceOf(LocalFileFailureStore.class);
                    assertThat(((LocalFileFailureStore) store).root()).isEqualTo(tempDir.toAbsolutePath());
                });
    }
}
