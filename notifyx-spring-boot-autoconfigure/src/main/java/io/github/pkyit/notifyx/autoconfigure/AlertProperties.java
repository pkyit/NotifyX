package io.github.pkyit.notifyx.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.pkyit.notifyx.core.AlertLevel;

/**
 * {@code notifyx.*} 配置项。
 */
@ConfigurationProperties(prefix = "notifyx")
public class AlertProperties {

    /** 是否启用告警能力。 */
    private boolean enabled = true;

    /** 低于该级别的事件直接丢弃，不进入限流与投递。 */
    private AlertLevel minLevel = AlertLevel.WARN;

    /** 默认事件来源，通常配置为 {@code ${spring.application.name}}。 */
    private String source = "application";

    private final Async async = new Async();

    private final RateLimit rateLimit = new RateLimit();

    private final Channels channels = new Channels();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AlertLevel getMinLevel() {
        return minLevel;
    }

    public void setMinLevel(AlertLevel minLevel) {
        this.minLevel = minLevel;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Async getAsync() {
        return async;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Channels getChannels() {
        return channels;
    }

    /**
     * 异步投递线程池配置。
     */
    public static class Async {

        /** 关闭后改为调用方线程同步投递。 */
        private boolean enabled = true;

        private int corePoolSize = 2;

        private int maxPoolSize = 8;

        private int queueCapacity = 1024;

        private Duration keepAlive = Duration.ofSeconds(60);

        private String threadNamePrefix = "notifyx-";

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
    }

    /**
     * 单机滑动窗口限流配置。
     */
    public static class RateLimit {

        private boolean enabled = false;

        /** 窗口内允许的最大投递次数。 */
        private int maxEvents = 10;

        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    /**
     * 内置通道开关。
     */
    public static class Channels {

        private final Log log = new Log();

        private final Webhook webhook = new Webhook();

        public Log getLog() {
            return log;
        }

        public Webhook getWebhook() {
            return webhook;
        }
    }

    /**
     * 日志通道。
     */
    public static class Log {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 通用 Webhook 通道：把事件以 JSON POST 出去，可对接钉钉/飞书/企业微信的自定义机器人中转服务。
     */
    public static class Webhook {

        private boolean enabled = false;

        private String url;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration timeout = Duration.ofSeconds(3);

        private Map<String, String> headers = new LinkedHashMap<>();

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

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
