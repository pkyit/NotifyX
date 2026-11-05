package io.github.pkyit.notifyx.autoconfigure;

import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 总开关关闭时的降级装配。
 *
 * <p>背景：{@code notifyx.enabled=false} 时，{@link NotifyxAutoConfiguration} 中的
 * {@code NotifyKit} 各分支（local / mq）均不创建。但业务方可能仍持有 {@code NotifyKit}
 * 的构造器强依赖，若容器中无该 Bean，将导致应用启动失败。</p>
 *
 * <p>本类在总开关关闭时提供一个 Null Object 实现，使依赖注入保持完整，
 * 同时所有通知调用静默空转，实现「总开关控制整个组件停用」的语义。</p>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>本类与 {@link NotifyxAutoConfiguration} 的 {@code NotifyKit} 分支互斥——
 *       后者带 {@code havingValue=true}，本类带 {@code havingValue=false}；</li>
 *   <li>{@code @ConditionalOnMissingBean} 允许业务方在关闭态自行提供实现；</li>
 *   <li>空转不产生日志，避免关闭态仍打印「已投递」等误导信息。</li>
 * </ul>
 *
 * @since 1.1
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx", name = "enabled", havingValue = "false")
public class NotifyxDisabledAutoConfiguration {

    /**
     * 关闭态的空转门面。
     *
     * @return 空实现
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyKit notifyxNoopNotifyKit() {
        return new NoopNotifyKit();
    }

    /**
     * Null Object：所有方法空转。
     *
     * <p>线程安全：无任何可变状态。</p>
     */
    static final class NoopNotifyKit implements NotifyKit {

        @Override
        public void send(NotifyEvent event) {
            // 关闭态，静默丢弃
        }

        @Override
        public void send(String scene, Map<String, ?> params) {
            // 关闭态，静默丢弃
        }

        @Override
        public void send(String scene, String receiver, Map<String, ?> params) {
            // 关闭态，静默丢弃
        }

        @Override
        public void alarm(Throwable throwable) {
            // 关闭态，静默丢弃
        }

        @Override
        public void alarm(String scene, Throwable throwable, Object... context) {
            // 关闭态，静默丢弃
        }

        @Override
        public void sendAsync(NotifyEvent event) {
            // 关闭态，静默丢弃
        }
    }
}
