package io.github.pkyit.notifyx.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.pkyit.notifyx.autoconfigure.channel.WebhookAlertChannel;

/**
 * Webhook 通道自动装配，默认关闭；需要显式配置 {@code notifyx.channels.webhook.url} 才启用。
 */
@AutoConfiguration(after = AlertAutoConfiguration.class)
@ConditionalOnProperty(prefix = "notifyx.channels.webhook", name = "enabled", havingValue = "true")
public class WebhookChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WebhookAlertChannel webhookAlertChannel(AlertProperties properties,
                                                   ObjectProvider<ObjectMapper> objectMapper) {
        ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
        return new WebhookAlertChannel(properties.getChannels().getWebhook(), mapper);
    }
}
