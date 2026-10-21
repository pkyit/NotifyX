package io.github.pkyit.notifyx.logback;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.api.NotifyKit;

/**
 * Logback 绑定自动装配（模块内自动配置，见 D-004/D-005）。
 *
 * <p>只在「有 logback + 有 NotifyKit」时生效；非 Spring 应用或非 logback 后端不受影响。
 * 不使用该能力时把 {@code notifyx-logback} 从依赖里去掉即可。</p>
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(afterName = "io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration")
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
@ConditionalOnProperty(prefix = "notifyx.logback", name = "enabled", matchIfMissing = true)
public class NotifyxLogbackAutoConfiguration {

    /**
     * 绑定器：把门面注入 logback 中已挂载的 {@link NotifyxAppender}。
     *
     * @param notifyKit 门面
     * @return 绑定器
     */
    @Bean
    @ConditionalOnBean(NotifyKit.class)
    @ConditionalOnMissingBean
    public NotifyxAppenderBinder notifyxAppenderBinder(NotifyKit notifyKit) {
        return new NotifyxAppenderBinder(notifyKit);
    }
}
