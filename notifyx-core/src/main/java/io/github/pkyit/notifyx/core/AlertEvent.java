package io.github.pkyit.notifyx.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条不可变告警事件，是核心层与所有通道之间唯一的数据契约。
 *
 * <p>事件只描述“发生了什么”，不携带任何投递细节；通道如何渲染、发送，
 * 完全由通道自己决定。</p>
 */
public final class AlertEvent {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long id;
    private final AlertLevel level;
    private final String title;
    private final String content;
    private final String source;
    private final Instant timestamp;
    private final String fingerprint;
    private final Map<String, String> tags;
    private final Map<String, Object> attributes;

    private AlertEvent(Builder builder) {
        this.id = SEQUENCE.incrementAndGet();
        this.level = Objects.requireNonNull(builder.level, "level");
        this.title = requireText(builder.title, "title");
        this.content = builder.content == null ? "" : builder.content;
        this.source = requireText(builder.source, "source");
        this.timestamp = builder.timestamp == null ? Instant.now() : builder.timestamp;
        this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tags));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
        this.fingerprint = builder.fingerprint == null
                ? defaultFingerprint(this.source, this.level, this.title)
                : requireText(builder.fingerprint, "fingerprint");
    }

    public static Builder builder(AlertLevel level, String title) {
        return new Builder(level, title);
    }

    public static Builder of(AlertLevel level, String title) {
        return builder(level, title);
    }

    private static String defaultFingerprint(String source, AlertLevel level, String title) {
        return source + "#" + level.name() + "#" + title;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** 进程内单调递增序号，仅用于排序与排查，不参与去重。 */
    public long id() {
        return id;
    }

    public AlertLevel level() {
        return level;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    /** 事件来源，例如服务名、模块名或业务域。 */
    public String source() {
        return source;
    }

    public Instant timestamp() {
        return timestamp;
    }

    /**
     * 用于限流/去重的稳定标识；默认由 source + level + title 组合而成。
     */
    public String fingerprint() {
        return fingerprint;
    }

    /** 结构化标签，便于通道渲染与检索。 */
    public Map<String, String> tags() {
        return tags;
    }

    /** 扩展属性，透传给通道（例如原始异常、业务主键）。 */
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "AlertEvent{id=" + id + ", level=" + level + ", title='" + title + "', source='" + source + "'}";
    }

    /**
     * {@link AlertEvent} 的构建器。必填项为 level、title、source。
     */
    public static final class Builder {

        private final AlertLevel level;
        private final String title;
        private String content = "";
        private String source = "application";
        private Instant timestamp;
        private String fingerprint;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Builder(AlertLevel level, String title) {
            this.level = level;
            this.title = title;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder fingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        public Builder tag(String key, String value) {
            if (key != null && value != null) {
                this.tags.put(key, value);
            }
            return this;
        }

        public Builder tags(Map<String, String> tags) {
            if (tags != null) {
                tags.forEach(this::tag);
            }
            return this;
        }

        public Builder attribute(String key, Object value) {
            if (key != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Builder attributes(Map<String, ?> attributes) {
            if (attributes != null) {
                attributes.forEach(this::attribute);
            }
            return this;
        }

        public AlertEvent build() {
            return new AlertEvent(this);
        }
    }
}
