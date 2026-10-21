package io.github.pkyit.notifyx.channel.log;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * 日志渠道自动装配，默认开启。
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx.channels.log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogChannelAutoConfiguration {

    /**
     * 日志渠道。
     *
     * @param environment 环境
     * @return 渠道
     */
    @Bean
    @ConditionalOnMissingBean(name = "notifyxLogChannel")
    public NotifyChannel notifyxLogChannel(Environment environment) {
        return new LogChannel(environment.getProperty("notifyx.channels.log.logger-name", "NOTIFYX_FALLBACK"));
    }
}