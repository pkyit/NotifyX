package io.github.pkyit.notifyx.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;
import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;

/**
 * Micrometer 指标自动装配：只有 classpath 存在 Micrometer 且容器里有 {@link MeterRegistry} 时生效。
 *
 * <p>用 {@code @ConditionalOnClass(name=...)} 做惰性探测，Micrometer 缺失时本类不会被加载，
 * 由核心自动装配的 no-op 实现兜底（承诺 P1）。</p>
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(before = NotifyxAutoConfiguration.class)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
public class NotifyxMetricsAutoConfiguration {

    /**
     * Micrometer 指标出口。
     *
     * @param registry 计量注册表
     * @return 指标出口
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyMetrics notifyxMicrometerMetrics(MeterRegistry registry) {
        return new MicrometerNotifyMetrics(registry);
    }
}