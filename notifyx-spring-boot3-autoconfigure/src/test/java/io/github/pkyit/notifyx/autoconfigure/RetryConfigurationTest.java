package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.exception.ChannelTimeoutException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * {@code notifyx.retry.enabled} 配置生效测试：关闭后每个渠道只尝试一次。
 *
 * @since 1.0
 */
class RetryConfigurationTest {

    private final FailingChannel channel = new FailingChannel();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class))
            .withBean(NotifyChannel.class, () -> channel)
            .withPropertyValues(
                    "notifyx.async.enabled=false",
                    "notifyx.scenes.SCENE_A.channels=failing",
                    "notifyx.scenes.SCENE_A.level=ERROR");

    @Test
    @DisplayName("默认按 max-attempts 重试")
    void shouldRetryByDefault() {
        runner.run(context -> {
            channel.calls.set(0);
            context.getBean(NotifyKit.class).send(event());
            assertThat(channel.calls).hasValue(3);
        });
    }

    @Test
    @DisplayName("notifyx.retry.enabled=false 时只尝试一次")
    void shouldDisableRetry() {
        runner.withPropertyValues("notifyx.retry.enabled=false")
                .run(context -> {
                    channel.calls.set(0);
                    context.getBean(NotifyKit.class).send(event());
                    assertThat(channel.calls).hasValue(1);
                });
    }

    private static NotifyEvent event() {
        return NotifyEvent.builder("SCENE_A").level(Level.ERROR).build();
    }

    private static final class FailingChannel implements NotifyChannel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Duration retryBackoff() {
            return Duration.ofMillis(1);
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            calls.incrementAndGet();
            throw new ChannelTimeoutException("failing", null);
        }
    }
}
