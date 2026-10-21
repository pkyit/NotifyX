package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.AlertChannel;
import io.github.pkyit.notifyx.core.AlertDispatcher;
import io.github.pkyit.notifyx.core.AlertEvent;

class AlertAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AlertAutoConfiguration.class,
                    LoggingChannelAutoConfiguration.class,
                    WebhookChannelAutoConfiguration.class));

    @Test
    void registersDefaultBeansWithLogChannel() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AlertDispatcher.class);
            assertThat(context).hasSingleBean(AlertTemplate.class);
            assertThat(context.getBean(AlertDispatcher.class).channelNames()).containsExactly("log");
        });
    }

    @Test
    void backsOffWhenDisabled() {
        contextRunner
                .withPropertyValues("notifyx.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AlertDispatcher.class);
                    assertThat(context).doesNotHaveBean(AlertTemplate.class);
                });
    }

    @Test
    void registersWebhookChannelWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "notifyx.channels.webhook.enabled=true",
                        "notifyx.channels.webhook.url=http://localhost:9999/hook")
                .run(context -> assertThat(context.getBean(AlertDispatcher.class).channelNames())
                        .containsExactlyInAnyOrder("log", "webhook"));
    }

    @Test
    void discoversUserDefinedChannels() {
        contextRunner
                .withBean("customChannel", AlertChannel.class, RecordingChannel::new)
                .run(context -> {
                    assertThat(context.getBean(AlertDispatcher.class).channelNames())
                            .contains("log", "custom");
                });
    }

    private static final class RecordingChannel implements AlertChannel {

        private final List<AlertEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "custom";
        }

        @Override
        public void send(AlertEvent event) {
            events.add(event);
        }
    }
}
