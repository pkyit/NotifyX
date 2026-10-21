package io.github.pkyit.notifyx.channel.webhook;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * Webhook 渠道自动装配，默认关闭。
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx.channels.webhook", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WebhookChannelProperties.class)
public class WebhookChannelAutoConfiguration {

    /**
     * Webhook 渠道。
     *
     * @param properties 配置
     * @return 渠道
     */
    @Bean
    @ConditionalOnMissingBean(name = "notifyxWebhookChannel")
    public NotifyChannel notifyxWebhookChannel(WebhookChannelProperties properties) {
        return new WebhookChannel(properties);
    }
}
