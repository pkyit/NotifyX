package org.springframework.boot.actuate.autoconfigure.metrics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 测试固件：模拟 Boot actuator 的 {@code MetricsAutoConfiguration}（与真实类同 FQCN）。
 *
 * <p>本模块对 actuator 是 optional 依赖（{@code @ConditionalOnClass(name=...)} 惰性探测），
 * 无法在测试里引用真实类；提供同名的 stub 使 {@link com.example} 无关的
 * {@code @AutoConfigureAfter(name=...)} 排序断言可以在单测中验证。
 * 仅存在于 test classpath。</p>
 *
 * @since 1.0.1
 */
@AutoConfiguration
public class MetricsAutoConfiguration {

    /**
     * 提供 MeterRegistry，等价于真实环境里 SimpleMetricsExportAutoConfiguration 的角色。
     *
     * @return 计量注册表
     */
    @Bean
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
