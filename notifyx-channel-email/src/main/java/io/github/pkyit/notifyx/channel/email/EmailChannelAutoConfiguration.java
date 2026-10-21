package io.github.pkyit.notifyx.channel.email;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * 邮件渠道自动装配。
 *
 * <p>{@code @ConditionalOnClass} 用字符串名而非 {@code Class} 引用，避免类缺失时的
 * {@code NoClassDefFoundError}（设计书 14.4 装配规范 1）。</p>
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.mail.Transport")
@ConditionalOnProperty(prefix = "notifyx.channels.email", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EmailChannelProperties.class)
public class EmailChannelAutoConfiguration {

    /**
     * 邮件渠道。
     *
     * @param properties 配置
     * @return 渠道
     */
    @Bean
    @ConditionalOnMissingBean(name = "notifyxEmailChannel")
    public NotifyChannel notifyxEmailChannel(EmailChannelProperties properties) {
        return new EmailChannel(properties);
    }
}
