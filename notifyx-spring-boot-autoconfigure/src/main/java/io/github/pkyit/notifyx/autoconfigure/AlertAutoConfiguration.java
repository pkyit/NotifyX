package io.github.pkyit.notifyx.autoconfigure;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.AlertChannel;
import io.github.pkyit.notifyx.core.AlertDispatcher;
import io.github.pkyit.notifyx.core.AlertRateLimiter;
import io.github.pkyit.notifyx.core.SlidingWindowRateLimiter;

/**
 * 告警核心自动装配。
 *
 * <p>所有 Bean 都带 {@link ConditionalOnMissingBean}：业务方可以随时用自己的实现替换
 * 调度器、限流器或模板，而不需要排除自动配置。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AlertProperties.class)
public class AlertAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AlertAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AlertRateLimiter alertRateLimiter(AlertProperties properties) {
        AlertProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return AlertRateLimiter.unlimited();
        }
        return new SlidingWindowRateLimiter(config.getMaxEvents(), config.getWindow());
    }

    @Bean
    @ConditionalOnMissingBean(name = "alertExecutor")
    public AlertExecutor alertExecutor(AlertProperties properties) {
        return properties.getAsync().isEnabled()
                ? AlertExecutor.async(properties.getAsync())
                : AlertExecutor.direct();
    }

    @Bean
    @ConditionalOnMissingBean
    public AlertDispatcher alertDispatcher(ObjectProvider<AlertChannel> channels,
                                           AlertExecutor alertExecutor,
                                           AlertRateLimiter rateLimiter,
                                           AlertProperties properties) {
        List<AlertChannel> ordered = channels.orderedStream().toList();
        log.info("notifyx enabled: channels={}, minLevel={}",
                ordered.stream().map(AlertChannel::name).toList(), properties.getMinLevel());

        return AlertDispatcher.builder()
                .channels(ordered)
                .executor(alertExecutor)
                .rateLimiter(rateLimiter)
                .filter(event -> event.level().atLeast(properties.getMinLevel()))
                .failureHandler(failure -> log.warn("alert delivery failed on channel [{}]: {}",
                        failure.channel(), failure.cause().toString()))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public AlertTemplate alertTemplate(AlertDispatcher dispatcher, AlertProperties properties) {
        return new AlertTemplate(dispatcher, properties.getSource());
    }
}
