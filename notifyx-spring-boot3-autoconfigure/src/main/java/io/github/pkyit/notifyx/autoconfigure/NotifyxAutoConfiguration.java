package io.github.pkyit.notifyx.autoconfigure;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.pipeline.Aggregator;
import io.github.pkyit.notifyx.core.pipeline.ChannelRegistry;
import io.github.pkyit.notifyx.core.pipeline.DefaultNotifyKit;
import io.github.pkyit.notifyx.core.pipeline.EscalationPolicy;
import io.github.pkyit.notifyx.core.pipeline.EventAggregator;
import io.github.pkyit.notifyx.core.pipeline.MessageTemplate;
import io.github.pkyit.notifyx.core.pipeline.MqNotifyKit;
import io.github.pkyit.notifyx.core.pipeline.NotifyPipeline;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.pipeline.RetryPolicy;
import io.github.pkyit.notifyx.core.pipeline.SceneConfig;
import io.github.pkyit.notifyx.core.pipeline.SceneRegistry;
import io.github.pkyit.notifyx.core.pipeline.filter.DedupeFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.GlobalMuteFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.GlobalRateLimitFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.QuietHoursFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.SceneRateLimitFilter;
import io.github.pkyit.notifyx.core.pipeline.filter.SceneRoutingFilter;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.Masker;
import io.github.pkyit.notifyx.core.spi.NotifyBus;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.RateLimiter;
import io.github.pkyit.notifyx.core.spi.TemplateEngine;
import io.github.pkyit.notifyx.core.spi.TraceContext;
import io.github.pkyit.notifyx.core.support.DefaultMasker;
import io.github.pkyit.notifyx.core.support.InMemoryFailureStore;
import io.github.pkyit.notifyx.core.support.LocalFileFailureStore;
import io.github.pkyit.notifyx.core.support.LoggingAuditRecorder;
import io.github.pkyit.notifyx.core.support.MdcTraceContext;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.MemoryRateLimiter;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;
import io.github.pkyit.notifyx.spring.aop.NotifyxAspect;
import io.github.pkyit.notifyx.spring.event.NotifyxEventBridge;
import io.github.pkyit.notifyx.spring.executor.PriorityNotifyExecutor;
import io.github.pkyit.notifyx.spring.spel.SpelEvaluator;

/**
 * 核心自动装配。
 *
 * <p>所有 Bean 均带 {@code @ConditionalOnMissingBean}：业务方自定义任意组件即可无缝替换。
 * 任何 SPI 缺失都走内置兜底，绝不因缺中间件导致启动失败（承诺 P1）。</p>
 *
 * <p>@implNote 装配期单线程执行，运行期 Bean 均线程安全。</p>
 *
 * @since 1.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(NotifyxProperties.class)
public class NotifyxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotifyxAutoConfiguration.class);

    /**
     * 模板引擎，默认 simple。
     *
     * @return 引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public TemplateEngine notifyxTemplateEngine() {
        return new SimpleTemplateEngine();
    }

    /**
     * 去重存储，默认内存实现。
     *
     * @param properties 配置
     * @return 去重存储
     */
    @Bean
    @ConditionalOnMissingBean
    public DedupeStore notifyxDedupeStore(NotifyxProperties properties) {
        return new MemoryDedupeStore(properties.getDedupe().getMaxEntries());
    }

    /**
     * 限流存储，默认内存滑动窗口。
     *
     * @return 限流器
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiter notifyxRateLimiter() {
        return new MemoryRateLimiter();
    }

    /**
     * 脱敏器。
     *
     * @return 脱敏器
     */
    @Bean
    @ConditionalOnMissingBean
    public Masker notifyxMasker() {
        return new DefaultMasker();
    }

    /**
     * 审计记录器，默认写日志。
     *
     * @return 审计器
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditRecorder notifyxAuditRecorder() {
        return new LoggingAuditRecorder();
    }

    /**
     * 失败存储：默认有界内存；{@code notifyx.fallback.store=file} 时落盘（设计书 9.9）。
     *
     * @param properties 配置
     * @return 失败存储
     */
    @Bean
    @ConditionalOnMissingBean
    public FailureStore notifyxFailureStore(NotifyxProperties properties) {
        NotifyxProperties.Fallback fallback = properties.getFallback();
        if ("file".equalsIgnoreCase(fallback.getStore())) {
            Path dir = fallback.getDir() == null || fallback.getDir().isBlank()
                    ? Path.of(System.getProperty("user.home"), ".notifyx", "pending")
                    : Path.of(fallback.getDir());
            return new LocalFileFailureStore(dir, fallback.getMaxFiles(), fallback.getMaxBytes().toBytes());
        }
        return new InMemoryFailureStore();
    }

    /**
     * 失败自动补偿（设计书 9.9）：默认关闭，开启后定时扫描并重投。
     *
     * @param notifyKit 门面
     * @param failureStore 失败存储
     * @param properties 配置
     * @return 补偿器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "notifyx.fallback.compensation", name = "enabled", havingValue = "true")
    public FailureCompensationRunner notifyxCompensationRunner(NotifyKit notifyKit, FailureStore failureStore,
                                                               NotifyxProperties properties) {
        NotifyxProperties.Compensation compensation = properties.getFallback().getCompensation();
        return new FailureCompensationRunner(notifyKit, failureStore,
                new FailureCompensationRunner.Config(compensation.isEnabled(), compensation.getInterval(),
                        compensation.getBatchSize(), compensation.getMaxAttempts(),
                        compensation.getRetryBackoff()));
    }

    /**
     * SpEL 求值器。
     *
     * @param properties 配置
     * @return 求值器
     */
    @Bean
    @ConditionalOnMissingBean
    public SpelEvaluator notifyxSpelEvaluator(NotifyxProperties properties) {
        return new SpelEvaluator(properties.getSpel().isAllowMethodInvocation());
    }

    /**
     * 指标出口兜底：Micrometer 未就绪时使用 no-op，保证零依赖可跑。
     *
     * @return 指标出口
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyMetrics notifyxMetrics() {
        return NotifyMetrics.noop();
    }

    /**
     * Trace 读取口：默认读 MDC（Micrometer Tracing / SkyWalking 都会写入）。
     *
     * @return trace 上下文
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceContext notifyxTraceContext() {
        return new MdcTraceContext();
    }

    /**
     * 投递执行器：异步走优先级队列，关闭或禁用时同步执行。
     *
     * @param properties 配置
     * @param metrics 指标出口
     * @return 执行器
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyTaskExecutor notifyxTaskExecutor(NotifyxProperties properties, NotifyMetrics metrics) {
        NotifyxProperties.Async async = properties.getAsync();
        if (!async.isEnabled()) {
            return NotifyTaskExecutor.direct();
        }
        return new PriorityNotifyExecutor(async.getCorePoolSize(), async.getMaxPoolSize(), async.getQueueCapacity(),
                async.getKeepAlive(), async.getThreadNamePrefix(), metrics, async.getShutdownAwait());
    }

    /**
     * 场景注册表。
     *
     * @param properties 配置
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public SceneRegistry notifyxSceneRegistry(NotifyxProperties properties) {
        Map<String, SceneConfig> scenes = new LinkedHashMap<>();
        properties.getScenes().forEach((code, scene) -> scenes.put(code, SceneConfig.builder(code)
                .enabled(scene.isEnabled())
                .level(scene.getLevel() == null ? properties.getDefaults().getLevel() : scene.getLevel())
                .channels(scene.getChannels().isEmpty() ? properties.getDefaults().getChannels() : scene.getChannels())
                .template(scene.getTemplate() == null ? properties.getDefaults().getTemplate() : scene.getTemplate())
                .receivers(scene.getReceivers().isEmpty() ? properties.getDefaults().getReceivers() : scene.getReceivers())
                .receiversFrom(scene.getReceiversFrom())
                .silentWindow(scene.getSilentWindow() == null
                        ? properties.getDefaults().getSilentWindow() : scene.getSilentWindow())
                .retry(scene.getRetry())
                .build()));
        SceneConfig defaults = SceneConfig.builder("__defaults__")
                .enabled(true)
                .level(properties.getDefaults().getLevel())
                .channels(properties.getDefaults().getChannels())
                .template(properties.getDefaults().getTemplate())
                .receivers(properties.getDefaults().getReceivers())
                .silentWindow(properties.getDefaults().getSilentWindow())
                .build();
        return SceneRegistry.of(scenes, defaults);
    }

    /**
     * 模板注册表。
     *
     * @param properties 配置
     * @return 模板 Map
     */
    @Bean
    @ConditionalOnMissingBean(name = "notifyxTemplates")
    public Map<String, MessageTemplate> notifyxTemplates(NotifyxProperties properties, Environment environment) {
        Map<String, MessageTemplate> templates = new LinkedHashMap<>();
        templates.put("default", new MessageTemplate("default", "[${level}] ${scene}", "${content}"));
        rawTemplateConfig(properties, environment).getTemplates().forEach((code, template) ->
                templates.put(code, new MessageTemplate(code, template.getTitle(), template.getContent())));
        return templates;
    }

    /**
     * 用不做占位符替换的 Binder 读取模板配置。
     *
     * <p><b>为什么必须这样</b>：模板语法是 <code>${userName}</code>，而 Spring 的
     * {@code @ConfigurationProperties} 绑定默认会把 <code>${...}</code> 当环境变量解析
     * （Windows 的 {@code USERNAME=admin} 会把模板里的 userName 悄悄替换成 admin）。
     * 模板占位符与 Spring 占位符必须隔离，否则参数名碰巧等于环境变量就会被静默篡改。</p>
     *
     * @param properties 已绑定的配置（兜底）
     * @param environment 环境
     * @return 原始模板配置
     */
    private static NotifyxProperties.Templates rawTemplateConfig(NotifyxProperties properties, Environment environment) {
        try {
            Binder binder = new Binder(ConfigurationPropertySources.get(environment), value -> value);
            return binder.bind("notifyx.templates", Bindable.of(NotifyxProperties.Templates.class))
                    .orElse(properties.getTemplates());
        } catch (RuntimeException ex) {
            log.warn("notifyx: raw template binding failed, fallback to bound values: {}", ex.toString());
            return properties.getTemplates();
        }
    }

    /**
     * 门面。
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
     * @param metrics 指标
     * @param taskExecutor 执行器
     * @param traceContext trace 读取口
     * @param customFilters 用户自定义过滤器
     * @param environment 环境
     * @return 门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "notifyx", name = "mode", havingValue = "local", matchIfMissing = true)
    public NotifyKit notifyxNotifyKit(NotifyxProperties properties,
                                      ObjectProvider<NotifyChannel> channels,
                                      TemplateEngine templateEngine,
                                      Map<String, MessageTemplate> templates,
                                      DedupeStore dedupeStore,
                                      RateLimiter rateLimiter,
                                      FailureStore failureStore,
                                      AuditRecorder auditRecorder,
                                      Masker masker,
                                      NotifyMetrics metrics,
                                      NotifyTaskExecutor taskExecutor,
                                      TraceContext traceContext,
                                      EventAggregator aggregator,
                                      EscalationPolicy escalation,
                                      ObjectProvider<EventFilter> customFilters,
                                      Environment environment) {
        List<EventFilter> filters = buildFilters(properties, dedupeStore, rateLimiter);
        customFilters.orderedStream().forEach(filters::add);
        filters.sort(Comparator.comparingInt(EventFilter::order));
        NotifyPipeline pipeline = NotifyPipeline.builder()
                .sceneRegistry(notifyxSceneRegistry(properties))
                .filters(filters)
                .channelRegistry(ChannelRegistry.of(channels.orderedStream().toList()))
                .templateEngine(templateEngine)
                .templates(templates)
                .dedupe(dedupeStore)
                .failureStore(failureStore)
                .auditRecorder(auditRecorder)
                .masker(masker)
                .metrics(metrics)
                .retryPolicy(new RetryPolicy(properties.getRetry().isEnabled()
                        ? properties.getRetry().getMaxAttempts() : 1,
                        properties.getRetry().getBaseDelay(), properties.getRetry().getMaxDelay(),
                        properties.getRetry().getJitter()))
                .instanceId(resolveInstanceId(properties, environment))
                .maskBeforeAudit(properties.getMask().isBeforeAudit())
                .circuitConfig(new NotifyPipeline.CircuitConfig(
                        properties.getChannels().getCircuitBreaker().getFailureThreshold(),
                        properties.getChannels().getCircuitBreaker().getOpenDuration(),
                        properties.getChannels().getCircuitBreaker().getMaxOpenDuration()))
                .traceContext(traceContext)
                .aggregator(aggregator)
                .escalation(escalation)
                .build();
        aggregator.sink(pipeline::handle);
        log.info("notifyx pipeline assembled: filters={}, channels={}, escalationRules={}",
                filters.size(), channels.orderedStream().map(NotifyChannel::name).toList(),
                escalation.ruleCount());
        return new DefaultNotifyKit(pipeline, taskExecutor, traceContext);
    }

    /**
     * MQ 模式门面（producer-only）：只发布报文，不消费、不渲染。
     *
     * @param bus 总线
     * @param failureStore 失败兜底
     * @param metrics 指标
     * @param properties 配置
     * @param environment 环境
     * @return 门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "notifyx", name = "mode", havingValue = "mq")
    public NotifyKit notifyxMqNotifyKit(NotifyBus bus, FailureStore failureStore, NotifyMetrics metrics,
                                        NotifyxProperties properties, Environment environment) {
        return new MqNotifyKit(bus, failureStore, metrics, resolveInstanceId(properties, environment));
    }

    /**
     * 实例内聚合器（设计书 9.5 T0）。默认关闭；多实例场景需 Redis/MQ 才能跨实例聚合。
     *
     * @param properties 配置
     * @return 聚合器
     */
    @Bean
    @ConditionalOnMissingBean
    public EventAggregator notifyxAggregator(NotifyxProperties properties) {
        NotifyxProperties.Aggregate config = properties.getAggregate();
        return new Aggregator(new Aggregator.Config(config.isEnabled(), config.getWindow(),
                config.getMaxEntries()));
    }

    /**
     * 升级策略（设计书 9.6）。默认关闭；T0 内存计数，多实例各自计数。
     *
     * @param properties 配置
     * @return 策略
     */
    @Bean
    @ConditionalOnMissingBean
    public EscalationPolicy notifyxEscalationPolicy(NotifyxProperties properties) {
        NotifyxProperties.Escalation config = properties.getEscalation();
        if (!config.isEnabled() || config.getRules().isEmpty()) {
            return EscalationPolicy.disabled();
        }
        List<EscalationPolicy.Rule> rules = new ArrayList<>();
        for (NotifyxProperties.Escalation.Rule rule : config.getRules()) {
            if (rule.getScene() == null || rule.getScene().isBlank()) {
                continue;
            }
            rules.add(new EscalationPolicy.Rule(rule.getScene(), rule.getConsecutive(), rule.getWithin(),
                    rule.getUpgradeTo(), rule.isResetOnSuccess()));
        }
        return new EscalationPolicy(rules, config.getResetScenes());
    }

    /**
     * L3 Spring Event 桥接：业务只发事件，桥接层翻译成 NotifyEvent（设计书 8.4）。
     *
     * @param notifyKit 门面
     * @param properties 配置
     * @return 桥接器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "notifyx.events", name = "enabled", matchIfMissing = true)
    public NotifyxEventBridge notifyxEventBridge(NotifyKit notifyKit, NotifyxProperties properties) {
        return new NotifyxEventBridge(notifyKit, properties.getEvents().getMappings());
    }

    /**
     * 优雅关闭：先排空通知队列，再释放渠道资源（设计书 9.10）。
     *
     * @param taskExecutor 执行器
     * @param channels 渠道
     * @param properties 配置
     * @return 生命周期
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyxLifecycle notifyxLifecycle(NotifyTaskExecutor taskExecutor,
                                             ObjectProvider<NotifyChannel> channels,
                                             NotifyxProperties properties,
                                             EventAggregator aggregator) {
        return new NotifyxLifecycle(taskExecutor, channels.orderedStream().toList(),
                properties.getAsync().getShutdownAwait(), aggregator);
    }

    /**
     * 启动诊断表（设计书 14.4 第 6 条）。
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
     * @return 诊断器
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyxDiagnostics notifyxDiagnostics(NotifyxProperties properties,
                                                 ObjectProvider<NotifyChannel> channels,
                                                 TemplateEngine templateEngine,
                                                 Map<String, MessageTemplate> templates,
                                                 DedupeStore dedupeStore,
                                                 RateLimiter rateLimiter,
                                                 FailureStore failureStore,
                                                 AuditRecorder auditRecorder,
                                                 Masker masker,
                                                 NotifyTaskExecutor taskExecutor) {
        return new NotifyxDiagnostics(properties, channels.orderedStream().toList(), templateEngine, templates,
                dedupeStore, rateLimiter, failureStore, auditRecorder, masker, taskExecutor);
    }

    /**
     * 注解切面。
     *
     * @param notifyKit 门面
     * @param spelEvaluator 求值器
     * @return 切面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    public NotifyxAspect notifyxAspect(NotifyKit notifyKit, SpelEvaluator spelEvaluator) {
        return new NotifyxAspect(notifyKit, spelEvaluator);
    }

    private List<EventFilter> buildFilters(NotifyxProperties properties, DedupeStore dedupeStore,
                                           RateLimiter rateLimiter) {
        List<EventFilter> filters = new ArrayList<>();
        filters.add(new GlobalMuteFilter(new AtomicBoolean(properties.getGlobalMute().isEnabled()),
                properties.getGlobalMute().isMuteCritical()));
        filters.add(new SceneRoutingFilter());
        NotifyxProperties.QuietHours quietHours = properties.getQuietHours();
        if (quietHours.isEnabled()) {
            filters.add(new QuietHoursFilter(LocalTime.parse(quietHours.getFrom()), LocalTime.parse(quietHours.getTo()),
                    ZoneId.of(quietHours.getTimezone()), quietHours.isDefer(), quietHours.isCriticalBypass()));
        }
        if (properties.getDedupe().isEnabled()) {
            filters.add(new DedupeFilter(dedupeStore));
        }
        if (properties.getRateLimit().isEnabled()) {
            Map<String, SceneRateLimitFilter.Rule> rules = new LinkedHashMap<>();
            Integer globalLimit = null;
            Duration globalWindow = null;
            for (NotifyxProperties.Rule rule : properties.getRateLimit().getRules()) {
                if (rule.isGlobal()) {
                    globalLimit = rule.getLimit();
                    globalWindow = rule.getWindow();
                } else if (rule.getScene() != null) {
                    rules.put(rule.getScene(), new SceneRateLimitFilter.Rule(rule.getLimit(), rule.getWindow()));
                }
            }
            filters.add(new SceneRateLimitFilter(rateLimiter, rules));
            if (globalLimit != null) {
                filters.add(new GlobalRateLimitFilter(rateLimiter, globalLimit, globalWindow));
            }
        }
        return filters;
    }

    private String resolveInstanceId(NotifyxProperties properties, Environment environment) {
        if (properties.getInstanceId() != null && !properties.getInstanceId().isBlank()) {
            return properties.getInstanceId();
        }
        String app = environment.getProperty("spring.application.name", "application");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "localhost";
        }
        return app + "-" + host + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }
}
