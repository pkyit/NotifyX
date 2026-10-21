package io.github.pkyit.notifyx.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.autoconfigure.channel.LoggingAlertChannel;

/**
 * 日志通道自动装配，默认开启。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx.channels.log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoggingChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LoggingAlertChannel loggingAlertChannel() {
        return new LoggingAlertChannel();
    }
}
