package io.github.pkyit.notifyx.core.model;

import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 业务语义事件：NotifyX 的唯一入参对象。
 *
 * <p>不可变、可序列化（MQ 模式下跨进程传输）。构造期完成全部防御式校验与拷贝，
 * 因此后续任何线程读取都无需加锁。</p>
 *
 * <p>@implNote 不可变，线程安全。{@code onSuccess}/{@code onError} 标记 transient，
 * MQ 模式下不参与序列化（设计书 6.4）。</p>
 *
 * @since 1.0
 */
public final class NotifyEvent implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** 场景码正则，见设计书 6.1。 */
    private static final Pattern SCENE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** params 允许的最大嵌套层数。 */
    private static final int MAX_PARAM_DEPTH = 3;

    /** params 序列化后的最大字节数（估算）。 */
    private static final int MAX_PARAM_BYTES = 64 * 1024;

    private final String scene;
    private final Level level;
    private final String title;
    private final Map<String, Object> params;
    private final Set<String> receivers;
    private final String bizId;
    private final String dedupeKey;
    private final Duration silentWindow;
    private final boolean async;
    private final Map<String, String> tags;
    private final long occurredAt;
    private final String eventId;

    private final transient Runnable onSuccess;
    private final transient Consumer<Throwable> onError;

    private NotifyEvent(Builder builder) {
        this.scene = requireScene(builder.scene);
        this.level = builder.level == null ? Level.WARN : builder.level;
        this.title = builder.title;
        this.params = normalizeParams(builder.params);
        this.receivers = normalizeReceivers(builder.receivers);
        this.bizId = builder.bizId;
        this.dedupeKey = builder.dedupeKey;
        this.silentWindow = builder.silentWindow;
        this.async = builder.async;
        this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tags));
        this.occurredAt = builder.occurredAt == 0L ? System.currentTimeMillis() : builder.occurredAt;
        this.eventId = builder.eventId == null ? "evt_" + io.github.pkyit.notifyx.core.support.Ulid.next() : builder.eventId;
        this.onSuccess = builder.onSuccess;
        this.onError = builder.onError;
    }

    /**
     * 创建构建器。
     *
     * @param scene 场景码，不可为 null
     * @return 构建器
     */
    public static Builder builder(String scene) {
        return new Builder(scene);
    }

    public String scene() {
        return scene;
    }

    public Level level() {
        return level;
    }

    /**
     * 事件标题，可为 null；未配置模板时作为标题使用。
     *
     * @return 标题或 null
     */
    public String title() {
        return title;
    }

    /**
     * 模板变量，已做防御性拷贝与不可变包装。
     *
     * @return 不可变 Map
     */
    public Map<String, Object> params() {
        return params;
    }

    /**
     * 指定的接收人；为空时由场景配置决定。
     *
     * @return 不可变 Set
     */
    public Set<String> receivers() {
        return receivers;
    }

    public String bizId() {
        return bizId;
    }

    /**
     * 自定义去重指纹，为空时由指纹算法生成（设计书 9.1）。
     *
     * @return 指纹或 null
     */
    public String dedupeKey() {
        return dedupeKey;
    }

    /**
     * 本事件的静默期，覆盖全局配置；null 表示不覆盖。
     *
     * @return 静默期或 null
     */
    public Duration silentWindow() {
        return silentWindow;
    }

    public boolean async() {
        return async;
    }

    public Map<String, String> tags() {
        return tags;
    }

    public long occurredAt() {
        return occurredAt;
    }

    public String eventId() {
        return eventId;
    }

    /**
     * 投递成功回调，仅 local 模式生效，可为 null。
     *
     * @return 回调或 null
     */
    public Runnable onSuccess() {
        return onSuccess;
    }

    /**
     * 投递失败回调，仅 local 模式生效，可为 null。
     *
     * @return 回调或 null
     */
    public Consumer<Throwable> onError() {
        return onError;
    }

    /**
     * 读取扩展标签。
     *
     * @param key 标签名
     * @return 标签值或 null
     */
    public String tag(String key) {
        return tags.get(key);
    }

    private static String requireScene(String scene) {
        Objects.requireNonNull(scene, "scene");
        if (!SCENE_PATTERN.matcher(scene).matches()) {
            throw new IllegalArgumentException(
                    "scene must match ^[A-Z][A-Z0-9_]{0,63}$ but was: " + scene);
        }
        return scene;
    }

    private static Set<String> normalizeReceivers(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String receiver : raw) {
            if (receiver == null) {
                continue;
            }
            String trimmed = receiver.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, Object> normalizeParams(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            copy.put(entry.getKey(), normalizeValue(entry.getValue(), 1));
        }
        if (estimateBytes(copy) > MAX_PARAM_BYTES) {
            throw new IllegalArgumentException("params exceed " + MAX_PARAM_BYTES + " bytes after normalization");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object normalizeValue(Object value, int depth) {
        if (value == null) {
            return "__NULL__";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof Temporal) {
            return value.toString();
        }
        if (depth >= MAX_PARAM_DEPTH) {
            return String.valueOf(value);
        }
        if (value.getClass().isArray()) {
            // 数组必须逐元素归一化：String.valueOf(Object[]) 是 identity hash，
            // 会让去重指纹每次调用都不同（@AlarmProtect 的 args 参数曾因此失效）。
            int length = Array.getLength(value);
            List<Object> nested = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                nested.add(normalizeValue(Array.get(value, i), depth + 1));
            }
            return Collections.unmodifiableList(nested);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                nested.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> nested = new ArrayList<>(collection.size());
            for (Object item : collection) {
                nested.add(normalizeValue(item, depth + 1));
            }
            return Collections.unmodifiableList(nested);
        }
        return String.valueOf(value);
    }

    private static int estimateBytes(Map<String, Object> params) {
        int total = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            total += entry.getKey().length() * 2;
            total += estimateValueBytes(entry.getValue());
        }
        return total;
    }

    private static int estimateValueBytes(Object value) {
        if (value == null) {
            return 8;
        }
        if (value instanceof Map<?, ?> map) {
            int total = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += String.valueOf(entry.getKey()).length() * 2;
                total += estimateValueBytes(entry.getValue());
            }
            return total;
        }
        if (value instanceof Collection<?> collection) {
            int total = 0;
            for (Object item : collection) {
                total += estimateValueBytes(item);
            }
            return total;
        }
        return String.valueOf(value).length() * 2;
    }

    @Override
    public String toString() {
        return "NotifyEvent{scene='" + scene + "', level=" + level + ", eventId='" + eventId + "'}";
    }

    /**
     * {@link NotifyEvent} 构建器。
     *
     * <p>@implNote 非线程安全，仅在构造期使用一次。</p>
     *
     * @since 1.0
     */
    public static final class Builder {

        private final String scene;
        private Level level = Level.WARN;
        private String title;
        private Map<String, Object> params = new LinkedHashMap<>();
        private Set<String> receivers = new LinkedHashSet<>();
        private String bizId;
        private String dedupeKey;
        private Duration silentWindow;
        private boolean async = true;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private long occurredAt;
        private String eventId;
        private Runnable onSuccess;
        private Consumer<Throwable> onError;

        private Builder(String scene) {
            this.scene = scene;
        }

        public Builder level(Level level) {
            this.level = level;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder param(String key, Object value) {
            if (key != null) {
                this.params.put(key, value);
            }
            return this;
        }

        public Builder params(Map<String, ?> params) {
            if (params != null) {
                this.params.putAll(params);
            }
            return this;
        }

        public Builder receiver(String receiver) {
            if (receiver != null) {
                this.receivers.add(receiver);
            }
            return this;
        }

        public Builder receivers(Collection<String> receivers) {
            if (receivers != null) {
                this.receivers.addAll(receivers);
            }
            return this;
        }

        public Builder bizId(String bizId) {
            this.bizId = bizId;
            return this;
        }

        public Builder dedupeKey(String dedupeKey) {
            this.dedupeKey = dedupeKey;
            return this;
        }

        public Builder silentWindow(Duration silentWindow) {
            this.silentWindow = silentWindow;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
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
                this.tags.putAll(tags);
            }
            return this;
        }

        public Builder occurredAt(long occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder onSuccess(Runnable onSuccess) {
            this.onSuccess = onSuccess;
            return this;
        }

        public Builder onError(Consumer<Throwable> onError) {
            this.onError = onError;
            return this;
        }

        /**
         * 构建事件，执行全部校验。
         *
         * @return 不可变事件
         * @throws IllegalArgumentException scene 非法或 params 超限时抛出
         */
        public NotifyEvent build() {
            return new NotifyEvent(this);
        }
    }
}
