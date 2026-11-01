package io.github.pkyit.notifyx.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer 指标自动装配：只有 classpath 存在 Micrometer 且容器里有 {@link MeterRegistry} 时生效。
 *
 * <p>用 {@code @ConditionalOnClass(name=...)} 做惰性探测，Micrometer 缺失时本类不会被加载，
 * 由核心自动装配的 no-op 实现兜底（承诺 P1）。</p>
 *
 * <p><b>必须声明 after</b>：{@code @ConditionalOnBean(MeterRegistry.class)} 依赖评估顺序——
 * 真实应用中 {@code MeterRegistry} 由 Boot 的 {@code MetricsAutoConfiguration} /
 * {@code SimpleMetricsExportAutoConfiguration} 提供（iter-59 demo 验证发现：不声明 after 时
 * 本配置先于它们评估，条件恒不成立，整套 {@code notifyx.*} 指标静默缺失）。
 * 用 name 字符串引用，避免对 actuator 的编译期硬依赖。</p>
 *
 * <p>@implNote 装配期单线程执行。</p>
 *
 * @since 1.0
 */
@AutoConfiguration(before = NotifyxAutoConfiguration.class)
@AutoConfigureAfter(name = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"})
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "notifyx.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NotifyxProperties.class)
public class NotifyxMetricsAutoConfiguration {

    /**
     * Micrometer 指标出口。
     *
     * @param registry 计量注册表
     * @param properties 配置（指标前缀）
     * @return 指标出口
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyMetrics notifyxMicrometerMetrics(MeterRegistry registry, NotifyxProperties properties) {
        return new MicrometerNotifyMetrics(registry, properties.getMetrics().getPrefix());
    }
}
