package io.github.pkyit.notifyx.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import io.github.pkyit.notifyx.core.pipeline.MessageTemplate;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.Masker;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.RateLimiter;
import io.github.pkyit.notifyx.core.spi.TemplateEngine;

/**
 * 启动诊断表（设计书 §14.4 第 6 条）。
 *
 * <p>在 {@link ApplicationReadyEvent} 时打印一张能力表，一次性回答排查时最高频的三个问题：
 * 渠道为什么没发出去、去重/限流是不是降级成了单机内存、配置从哪来。</p>
 *
 * <p>只打印类名与数量，<b>绝不打印配置值</b>（避免把 SMTP 密码、webhook secret 写进日志）。</p>
 *
 * <p>@implNote 只在启动期执行一次，线程安全。</p>
 *
 * @since 1.0
 */
public final class NotifyxDiagnostics implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(NotifyxDiagnostics.class);

    private final NotifyxProperties properties;
    private final List<NotifyChannel> channels;
    private final TemplateEngine templateEngine;
    private final Map<String, MessageTemplate> templates;
    private final DedupeStore dedupeStore;
    private final RateLimiter rateLimiter;
    private final FailureStore failureStore;
    private final AuditRecorder auditRecorder;
    private final Masker masker;
    private final NotifyTaskExecutor taskExecutor;

    /**
     * 构造诊断器。
     *
     * @param properties 配置
     * @param channels 渠道
     * @param templateEngine 模板引擎
     * @param templates 模板表
     * @param dedupeStore 去重存储
     * @param rateLimiter 限流器
     * @param failureStore 失败存储
     * @param auditRecorder 审计器
     * @param masker 脱敏器
     * @param taskExecutor 执行器
     */
    public NotifyxDiagnostics(NotifyxProperties properties, List<NotifyChannel> channels,
                              TemplateEngine templateEngine, Map<String, MessageTemplate> templates,
                              DedupeStore dedupeStore, RateLimiter rateLimiter, FailureStore failureStore,
                              AuditRecorder auditRecorder, Masker masker, NotifyTaskExecutor taskExecutor) {
        this.properties = properties;
        this.channels = List.copyOf(channels);
        this.templateEngine = templateEngine;
        this.templates = Map.copyOf(templates);
        this.dedupeStore = dedupeStore;
        this.rateLimiter = rateLimiter;
        this.failureStore = failureStore;
        this.auditRecorder = auditRecorder;
        this.masker = masker;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.getDiagnostics().isBanner()) {
            return;
        }
        try {
            log.info("\n{}", render());
        } catch (RuntimeException ex) {
            // 诊断表失败绝不影响应用启动
            log.warn("notifyx diagnostics failed: {}", ex.toString());
        }
    }

    /**
     * 渲染诊断表（独立方法便于单测断言）。
     *
     * @return 多行文本
     */
    public String render() {
        StringBuilder builder = new StringBuilder(1024);
        String line = "─".repeat(66);
        builder.append("┌").append(line).append("┐\n");
        builder.append("│ NotifyX ").append(version()).append("  ·  mode=").append(properties.getMode())
                .append("  ·  tier=").append(capabilityTier()).append('\n');
        builder.append("├").append(line).append("┤\n");
        appendChannels(builder);
        builder.append("│ 场景：").append(properties.getScenes().size())
                .append(" 个   模板：").append(templates.size())
                .append(" 个   引擎：").append(templateEngine == null ? "none" : templateEngine.name()).append('\n');
        builder.append("├").append(line).append("┤\n");
        builder.append("│ SPI 装配\n");
        appendSpi(builder, "去重", dedupeStore);
        appendSpi(builder, "限流", rateLimiter);
        appendSpi(builder, "落盘", failureStore);
        appendSpi(builder, "审计", auditRecorder);
        appendSpi(builder, "脱敏", masker);
        builder.append("│ 执行器：").append(simpleName(taskExecutor)).append('\n');
        builder.append("├").append(line).append("┤\n");
        appendWarnings(builder);
        appendActuatorHint(builder);
        builder.append("└").append(line).append("┘");
        return builder.toString();
    }

    private void appendChannels(StringBuilder builder) {
        int enabled = 0;
        for (NotifyChannel channel : channels) {
            if (isEnabled(channel)) {
                enabled++;
            }
        }
        builder.append("│ 渠道（").append(enabled).append(" 启用 / ").append(channels.size()).append(" 已注册）\n");
        if (channels.isEmpty()) {
            builder.append("│   ⚠ 没有任何渠道：通知将无处可去\n");
            return;
        }
        for (NotifyChannel channel : channels) {
            boolean on = isEnabled(channel);
            builder.append("│   ").append(on ? "✅" : "⚪").append(' ')
                    .append(channel.name()).append("  ").append(simpleName(channel))
                    .append(on ? "" : "（未启用）").append('\n');
        }
    }

    private static boolean isEnabled(NotifyChannel channel) {
        try {
            return channel.isEnabled(null);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private void appendSpi(StringBuilder builder, String label, Object spi) {
        builder.append("│   ").append(pad(label)).append(' ').append(simpleName(spi));
        if (isMemory(spi)) {
            builder.append("  ⚠ 单机内存，多实例部署下能力受限");
        }
        builder.append('\n');
    }

    private void appendWarnings(StringBuilder builder) {
        builder.append("│ ⚠ 降级与告警\n");
        if (!properties.getQuietHours().isEnabled()) {
            builder.append("│   · 免打扰未启用，夜间告警会正常发送\n");
        }
        if (properties.getGlobalMute().isEnabled()) {
            builder.append("│   · 全局静默已开启：所有通知被丢弃\n");
        }
        builder.append("│   · 熔断阈值 ").append(properties.getChannels().getCircuitBreaker().getFailureThreshold())
                .append(" 次 / ").append(properties.getChannels().getCircuitBreaker().getOpenDuration()).append('\n');
        if (properties.getDedupe().isEnabled()) {
            builder.append("│   · 去重窗口默认 ").append(properties.getDedupe().getDefaultWindow()).append('\n');
        }
        if (properties.getAggregate().isEnabled()) {
            builder.append("│   · 聚合：实例内 ")
                    .append(properties.getAggregate().getWindow().toSeconds())
                    .append("s 窗口（多实例不合并，T1/T2 才跨实例）\n");
        } else {
            builder.append("│   · 聚合未启用：重复事件只去重不汇总\n");
        }
        if (properties.getEscalation().isEnabled()) {
            builder.append("│   · 升级：").append(properties.getEscalation().getRules().size())
                    .append(" 条规则（T0 实例内计数，多实例各自计数）\n");
        } else {
            builder.append("│   · 升级未启用：高频故障不会自动切到更高触达渠道\n");
        }
    }

    private void appendActuatorHint(StringBuilder builder) {
        String hint = classPresent("org.springframework.boot.actuate.health.HealthIndicator")
                ? "已检测到 actuator，健康信息见 /actuator/health（notifyx）"
                : "未检测到 spring-boot-actuator，跳过健康端点";
        builder.append("│ ").append(hint).append('\n');
    }

    private String capabilityTier() {
        if (isMemory(dedupeStore) || isMemory(rateLimiter)) {
            return "T0（单机内存）";
        }
        return "T1+（外部存储）";
    }

    private static String version() {
        String version = NotifyxDiagnostics.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static boolean isMemory(Object spi) {
        if (spi == null) {
            return false;
        }
        String name = spi.getClass().getSimpleName().toLowerCase();
        return name.contains("memory") || name.contains("inmemory");
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, NotifyxDiagnostics.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    private static String simpleName(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName();
    }

    private static String pad(String label) {
        List<String> cells = new ArrayList<>();
        cells.add(label);
        while (String.join("", cells).length() < 4) {
            cells.add(" ");
        }
        return String.join("", cells);
    }
}
