package io.github.pkyit.notifyx.core.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.RenderFormat;

/**
 * 渠道执行上下文：渠道能看到的唯一世界。
 *
 * <p>渲染已在管线内完成（ADR-004），渠道只负责按自身报文格式打包已渲染内容。</p>
 *
 * <p>@implNote 不可变，线程安全；{@link #withAttempt(int)} 返回新实例。</p>
 *
 * @since 1.0
 */
public final class NotifyContext {

    private final NotifyEvent event;
    private final String renderedTitle;
    private final String renderedContent;
    private final RenderFormat renderFormat;
    private final Object channelConfig;
    private final String receiver;
    private final String traceId;
    private final String spanId;
    private final String instanceId;
    private final int attempt;
    private final String idempotencyKey;
    private final Map<ContextKey<?>, Object> attributes;

    private NotifyContext(Builder builder) {
        this.event = Objects.requireNonNull(builder.event, "event");
        this.renderedTitle = builder.renderedTitle;
        this.renderedContent = builder.renderedContent == null ? "" : builder.renderedContent;
        this.renderFormat = builder.renderFormat == null ? RenderFormat.PLAIN : builder.renderFormat;
        this.channelConfig = builder.channelConfig;
        this.receiver = builder.receiver;
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.instanceId = builder.instanceId;
        this.attempt = builder.attempt;
        this.idempotencyKey = builder.idempotencyKey;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    /**
     * 创建上下文构建器。
     *
     * @param event 原始事件
     * @return 构建器
     */
    public static Builder builder(NotifyEvent event) {
        return new Builder(event);
    }

    public NotifyEvent event() {
        return event;
    }

    public String renderedTitle() {
        return renderedTitle;
    }

    public String renderedContent() {
        return renderedContent;
    }

    public RenderFormat renderFormat() {
        return renderFormat;
    }

    /**
     * 渠道配置快照，由自动装配注入，可为 null。
     *
     * @return 配置对象或 null
     */
    public Object channelConfig() {
        return channelConfig;
    }

    /**
     * 以指定类型读取渠道配置。
     *
     * @param type 配置类型
     * @param <T> 配置类型
     * @return 配置实例
     * @throws ClassCastException 配置类型不匹配时抛出
     */
    public <T> T channelConfigAs(Class<T> type) {
        return type.cast(channelConfig);
    }

    /**
     * 单个接收人；批量渠道下可为 null。
     *
     * @return 接收人
     */
    public String receiver() {
        return receiver;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * 第几次尝试，从 1 开始。
     *
     * @return 尝试次数
     */
    public int attempt() {
        return attempt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * 读取类型化属性。
     *
     * @param key 键
     * @param <T> 值类型
     * @return 值，不存在时返回 null
     */
    public <T> T attribute(ContextKey<T> key) {
        Object value = attributes.get(key);
        return value == null ? null : key.type().cast(value);
    }

    /**
     * 复制上下文并指定新的尝试次数。
     *
     * @param nextAttempt 新的尝试次数
     * @return 新上下文
     */
    public NotifyContext withAttempt(int nextAttempt) {
        Builder builder = new Builder(event)
                .renderedTitle(renderedTitle)
                .renderedContent(renderedContent)
                .renderFormat(renderFormat)
                .channelConfig(channelConfig)
                .receiver(receiver)
                .traceId(traceId)
                .spanId(spanId)
                .instanceId(instanceId)
                .attempt(nextAttempt)
                .idempotencyKey(idempotencyKey);
        attributes.forEach((key, value) -> builder.attributeRaw(key, value));
        return builder.build();
    }

    /**
     * {@link NotifyContext} 构建器。
     *
     * <p>@implNote 非线程安全，仅构造期使用。</p>
     *
     * @since 1.0
     */
    public static final class Builder {

        private final NotifyEvent event;
        private String renderedTitle;
        private String renderedContent;
        private RenderFormat renderFormat = RenderFormat.PLAIN;
        private Object channelConfig;
        private String receiver;
        private String traceId;
        private String spanId;
        private String instanceId;
        private int attempt = 1;
        private String idempotencyKey;
        private final Map<ContextKey<?>, Object> attributes = new LinkedHashMap<>();

        private Builder(NotifyEvent event) {
            this.event = event;
        }

        public Builder renderedTitle(String renderedTitle) {
            this.renderedTitle = renderedTitle;
            return this;
        }

        public Builder renderedContent(String renderedContent) {
            this.renderedContent = renderedContent;
            return this;
        }

        public Builder renderFormat(RenderFormat renderFormat) {
            this.renderFormat = renderFormat;
            return this;
        }

        public Builder channelConfig(Object channelConfig) {
            this.channelConfig = channelConfig;
            return this;
        }

        public Builder receiver(String receiver) {
            this.receiver = receiver;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public <T> Builder attribute(ContextKey<T> key, T value) {
            if (key != null && value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        private Builder attributeRaw(ContextKey<?> key, Object value) {
            if (key != null && value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public NotifyContext build() {
            return new NotifyContext(this);
        }
    }
}
