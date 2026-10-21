package io.github.pkyit.notifyx.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.RateLimiter;

/**
 * Actuator 自动装配（设计书 §15.2）：只有 classpath 上真的有 actuator 时才生效。
 *
 * <p>用 {@code @ConditionalOnClass(name=...)} 字符串条件，未引 actuator 的应用不会因为
 * 缺类而启动失败（承诺 P1）。</p>
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(afterName = "io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
@ConditionalOnProperty(prefix = "notifyx", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "notifyx.actuator.health", name = "enabled", matchIfMissing = true)
public class NotifyxActuatorAutoConfiguration {

    /**
     * 健康指示器。
     *
     * @param properties 配置
     * @param channels 渠道
     * @param dedupeStore 去重存储
     * @param rateLimiter 限流器
     * @param failureStore 失败存储
     * @return 指示器
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyxHealthIndicator notifyxHealthIndicator(NotifyxProperties properties,
                                                         ObjectProvider<NotifyChannel> channels,
                                                         DedupeStore dedupeStore,
                                                         RateLimiter rateLimiter,
                                                         FailureStore failureStore) {
        return new NotifyxHealthIndicator(properties, channels.orderedStream().toList(),
                dedupeStore, rateLimiter, failureStore);
    }
}
