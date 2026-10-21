package io.github.pkyit.notifyx.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * {@code notifyx.*} 配置属性。手写 getter/setter（ADR-011 禁用 Lombok）。
 *
 * <p>@implNote 可变 POJO，仅在启动期由 Spring 绑定后只读使用，运行期不修改。</p>
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx")
public class NotifyxProperties {

    /** 总开关。 */
    private boolean enabled = true;
    /** 环境标识，渲染进模板。 */
    private String env = "prod";
    /** 租户标识。 */
    private String tenant = "default";
    /** 运行模式：local | mq。 */
    private String mode = "local";
    /** 实例 ID，留空自动生成。 */
    private String instanceId;
    private final Async async = new Async();
    private final Defaults defaults = new Defaults();
    private final Map<String, Scene> scenes = new LinkedHashMap<>();
    private final Channels channels = new Channels();
    private final Templates templates = new Templates();
    private final Dedupe dedupe = new Dedupe();
    private final RateLimit rateLimit = new RateLimit();
    private final Retry retry = new Retry();
    private final QuietHours quietHours = new QuietHours();
    private final GlobalMute globalMute = new GlobalMute();
    private final Metrics metrics = new Metrics();
    private final Mask mask = new Mask();
    private final Spel spel = new Spel();
    private final Diagnostics diagnostics = new Diagnostics();
    private final Aggregate aggregate = new Aggregate();
    private final Events events = new Events();
    private final Actuator actuator = new Actuator();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Async getAsync() {
        return async;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public Map<String, Scene> getScenes() {
        return scenes;
    }

    public Channels getChannels() {
        return channels;
    }

    public Templates getTemplates() {
        return templates;
    }

    public Dedupe getDedupe() {
        return dedupe;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Retry getRetry() {
        return retry;
    }

    public QuietHours getQuietHours() {
        return quietHours;
    }

    public GlobalMute getGlobalMute() {
        return globalMute;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Mask getMask() {
        return mask;
    }

    public Spel getSpel() {
        return spel;
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }

    public Aggregate getAggregate() {
        return aggregate;
    }

    public Events getEvents() {
        return events;
    }

    public Actuator getActuator() {
        return actuator;
    }

    /** 异步执行配置。 */
    public static class Async {
        private boolean enabled = true;
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 4096;
        private Duration keepAlive = Duration.ofSeconds(60);
        private String threadNamePrefix = "notifyx-";
        private Duration shutdownAwait = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(Duration keepAlive) {
            this.keepAlive = keepAlive;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public Duration getShutdownAwait() {
            return shutdownAwait;
        }

        public void setShutdownAwait(Duration shutdownAwait) {
            this.shutdownAwait = shutdownAwait;
        }
    }

    /** 默认值：未显式配置的场景沿用。 */
    public static class Defaults {
        private List<String> receivers = new ArrayList<>();
        private Level level = Level.WARN;
        private Duration silentWindow = Duration.ofMinutes(5);
        private String template = "default";
        private List<String> channels = new ArrayList<>();

        public List<String> getReceivers() {
            return receivers;
        }

        public void setReceivers(List<String> receivers) {
            this.receivers = receivers;
        }

        public Level getLevel() {
            return level;
        }

        public void setLevel(Level level) {
            this.level = level;
        }

        public Duration getSilentWindow() {
            return silentWindow;
        }

        public void setSilentWindow(Duration silentWindow) {
            this.silentWindow = silentWindow;
        }

        public String getTemplate() {
            return template;
        }

        public void setTemplate(String template) {
            this.template = template;
        }

        public List<String> getChannels() {
            return channels;
        }

        public void setChannels(List<String> channels) {
            this.channels = channels;
        }
    }

    /** 场景配置。 */
    public static class Scene {
        private boolean enabled = true;
        private Level level;
        private List<String> channels = new ArrayList<>();
        private String template;
        private List<String> receivers = new ArrayList<>();
        private String receiversFrom;
        private Duration silentWindow;
        private int retry;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Level getLevel() {
            return level;
        }

        public void setLevel(Level level) {
            this.level = level;
        }

        public List<String> getChannels() {
            return channels;
        }

        public void setChannels(List<String> channels) {
            this.channels = channels;
        }

        public String getTemplate() {
            return template;
        }

        public void setTemplate(String template) {
            this.template = template;
        }

        public List<String> getReceivers() {
            return receivers;
        }

        public void setReceivers(List<String> receivers) {
            this.receivers = receivers;
        }

        public String getReceiversFrom() {
            return receiversFrom;
        }

        public void setReceiversFrom(String receiversFrom) {
            this.receiversFrom = receiversFrom;
        }

        public Duration getSilentWindow() {
            return silentWindow;
        }

        public void setSilentWindow(Duration silentWindow) {
            this.silentWindow = silentWindow;
        }

        public int getRetry() {
            return retry;
        }

        public void setRetry(int retry) {
            this.retry = retry;
        }
    }

    /** 渠道配置。 */
    public static class Channels {
        private final CircuitBreaker circuitBreaker = new CircuitBreaker();
        private final Email email = new Email();
        private final Webhook webhook = new Webhook();
        private final Log log = new Log();

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public Email getEmail() {
            return email;
        }

        public Webhook getWebhook() {
            return webhook;
        }

        public Log getLog() {
            return log;
        }
    }

    /** 熔断配置。 */
    public static class CircuitBreaker {
        private boolean enabled = true;
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(30);
        private Duration maxOpenDuration = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }

        public Duration getMaxOpenDuration() {
            return maxOpenDuration;
        }

        public void setMaxOpenDuration(Duration maxOpenDuration) {
            this.maxOpenDuration = maxOpenDuration;
        }
    }

    /** 邮件渠道配置。 */
    public static class Email {
        private boolean enabled;
        private String host;
        private int port = 25;
        private boolean ssl;
        private boolean starttls;
        private String username;
        private String password;
        private String from;
        private String fromName = "NotifyX";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration writeTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public boolean isStarttls() {
            return starttls;
        }

        public void setStarttls(boolean starttls) {
            this.starttls = starttls;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getFromName() {
            return fromName;
        }

        public void setFromName(String fromName) {
            this.fromName = fromName;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getWriteTimeout() {
            return writeTimeout;
        }

        public void setWriteTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
        }
    }

    /** Webhook 渠道配置。 */
    public static class Webhook {
        private boolean enabled;
        private String url;
        private String secret;
        private boolean signEnabled = true;
        private Map<String, String> headers = new LinkedHashMap<>();
        private List<Integer> expectStatus = new ArrayList<>(List.of(200, 201, 204));
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public boolean isSignEnabled() {
            return signEnabled;
        }

        public void setSignEnabled(boolean signEnabled) {
            this.signEnabled = signEnabled;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public List<Integer> getExpectStatus() {
            return expectStatus;
        }

        public void setExpectStatus(List<Integer> expectStatus) {
            this.expectStatus = expectStatus;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /** 日志渠道配置。 */
    public static class Log {
        private boolean enabled = true;
        private String loggerName = "NOTIFYX_FALLBACK";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLoggerName() {
            return loggerName;
        }

        public void setLoggerName(String loggerName) {
            this.loggerName = loggerName;
        }
    }

    /** 模板配置。 */
    public static class Templates {
        private String engine = "simple";
        private final Map<String, Template> templates = new LinkedHashMap<>();

        public String getEngine() {
            return engine;
        }

        public void setEngine(String engine) {
            this.engine = engine;
        }

        public Map<String, Template> getTemplates() {
            return templates;
        }
    }

    /** 单个模板。 */
    public static class Template {
        private String title;
        private String content = "${content}";

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    /** 去重配置。 */
    public static class Dedupe {
        private boolean enabled = true;
        private String store = "auto";
        private Duration defaultWindow = Duration.ofMinutes(5);
        private int maxEntries = 100_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public Duration getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(Duration defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** 限流配置。 */
    public static class RateLimit {
        private boolean enabled = true;
        private String store = "auto";
        private List<Rule> rules = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public List<Rule> getRules() {
            return rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = rules;
        }
    }

    /** 限流规则。 */
    public static class Rule {
        private String scene;
        private boolean global;
        private int limit = 100;
        private Duration window = Duration.ofMinutes(1);

        public String getScene() {
            return scene;
        }

        public void setScene(String scene) {
            this.scene = scene;
        }

        public boolean isGlobal() {
            return global;
        }

        public void setGlobal(boolean global) {
            this.global = global;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    /** 重试配置。 */
    public static class Retry {
        private boolean enabled = true;
        private Duration baseDelay = Duration.ofSeconds(2);
        private Duration maxDelay = Duration.ofSeconds(60);
        private double jitter = 0.5;
        private int maxAttempts = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getBaseDelay() {
            return baseDelay;
        }

        public void setBaseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /** 免打扰配置。 */
    public static class QuietHours {
        private boolean enabled;
        private String from = "22:00";
        private String to = "09:00";
        private String timezone = "Asia/Shanghai";
        private boolean defer = true;
        private boolean criticalBypass = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public boolean isDefer() {
            return defer;
        }

        public void setDefer(boolean defer) {
            this.defer = defer;
        }

        public boolean isCriticalBypass() {
            return criticalBypass;
        }

        public void setCriticalBypass(boolean criticalBypass) {
            this.criticalBypass = criticalBypass;
        }
    }

    /** 全局静默配置。 */
    public static class GlobalMute {
        private boolean enabled;
        private boolean muteCritical = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isMuteCritical() {
            return muteCritical;
        }

        public void setMuteCritical(boolean muteCritical) {
            this.muteCritical = muteCritical;
        }
    }

    /** 指标配置。 */
    public static class Metrics {
        private boolean enabled = true;
        private String prefix = "notifyx";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }

    /** 脱敏配置。 */
    public static class Mask {
        private boolean enabled = true;
        private boolean beforeAudit = true;
        private boolean beforeSend;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isBeforeAudit() {
            return beforeAudit;
        }

        public void setBeforeAudit(boolean beforeAudit) {
            this.beforeAudit = beforeAudit;
        }

        public boolean isBeforeSend() {
            return beforeSend;
        }

        public void setBeforeSend(boolean beforeSend) {
            this.beforeSend = beforeSend;
        }
    }

    /** SpEL 配置。 */
    public static class Spel {
        private boolean allowMethodInvocation;

        public boolean isAllowMethodInvocation() {
            return allowMethodInvocation;
        }

        public void setAllowMethodInvocation(boolean allowMethodInvocation) {
            this.allowMethodInvocation = allowMethodInvocation;
        }
    }

    /** 诊断配置。 */
    public static class Diagnostics {
        private boolean banner = true;
        private boolean failFast;

        public boolean isBanner() {
            return banner;
        }

        public void setBanner(boolean banner) {
            this.banner = banner;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }
    }

    /** L3 Spring Event 桥接配置（设计书 8.4）。 */
    public static class Events {
        private boolean enabled = true;
        /** 事件类全限定名 → 场景码；事件类可以不依赖任何 NotifyX 注解。 */
        private final Map<String, String> mappings = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getMappings() {
            return mappings;
        }
    }

    /** Actuator 配置（设计书 15.2）。 */
    public static class Actuator {
        private final Health health = new Health();

        public Health getHealth() {
            return health;
        }

        /** 健康端点配置。 */
        public static class Health {
            private boolean enabled = true;
            /** 是否真实探活渠道；默认关闭，避免健康检查制造流量与费用。 */
            private boolean probeEnabled;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public boolean isProbeEnabled() {
                return probeEnabled;
            }

            public void setProbeEnabled(boolean probeEnabled) {
                this.probeEnabled = probeEnabled;
            }
        }
    }

    /** 聚合配置（设计书 9.5，T0 实例内聚合）。 */
    public static class Aggregate {
        private boolean enabled;
        private Duration window = Duration.ofSeconds(10);
        private int maxEntries = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }
}
