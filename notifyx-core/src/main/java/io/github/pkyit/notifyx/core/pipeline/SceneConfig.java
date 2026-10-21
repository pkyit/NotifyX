package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * 场景配置：模板、通道、接收人、静默期全部挂在 scene 上。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public final class SceneConfig {

    private final String scene;
    private final boolean enabled;
    private final Level level;
    private final List<String> channels;
    private final String template;
    private final List<String> receivers;
    private final String receiversFrom;
    private final Duration silentWindow;
    private final int retry;

    private SceneConfig(Builder builder) {
        this.scene = Objects.requireNonNull(builder.scene, "scene");
        this.enabled = builder.enabled;
        this.level = builder.level == null ? Level.WARN : builder.level;
        this.channels = List.copyOf(builder.channels);
        this.template = builder.template;
        this.receivers = List.copyOf(builder.receivers);
        this.receiversFrom = builder.receiversFrom;
        this.silentWindow = builder.silentWindow;
        this.retry = builder.retry;
    }

    /**
     * 创建构建器。
     *
     * @param scene 场景码
     * @return 构建器
     */
    public static Builder builder(String scene) {
        return new Builder(scene);
    }

    public String scene() {
        return scene;
    }

    public boolean enabled() {
        return enabled;
    }

    public Level level() {
        return level;
    }

    public List<String> channels() {
        return channels;
    }

    /**
     * 模板编码，为空时使用默认模板。
     *
     * @return 模板编码或 null
     */
    public String template() {
        return template;
    }

    public List<String> receivers() {
        return receivers;
    }

    /**
     * 从 params 取接收人的表达式（如 {@code params.receiver}），为空时不启用。
     *
     * @return 表达式或 null
     */
    public String receiversFrom() {
        return receiversFrom;
    }

    /**
     * 静默期；null 表示使用默认值。
     *
     * @return 静默期或 null
     */
    public Duration silentWindow() {
        return silentWindow;
    }

    /**
     * 覆盖全局重试次数；0 表示不覆盖。
     *
     * @return 重试次数
     */
    public int retry() {
        return retry;
    }

    /**
     * 返回一个覆盖了级别与通道的副本，用于事件级别高于场景级别时的提升。
     *
     * @param newLevel 新级别
     * @param newChannels 新通道列表
     * @return 新配置
     */
    public SceneConfig withRouting(Level newLevel, List<String> newChannels) {
        return builder(scene)
                .enabled(enabled)
                .level(newLevel)
                .channels(newChannels)
                .template(template)
                .receivers(receivers)
                .receiversFrom(receiversFrom)
                .silentWindow(silentWindow)
                .retry(retry)
                .build();
    }

    /**
     * {@link SceneConfig} 构建器。
     *
     * <p>@implNote 非线程安全，仅构造期使用。</p>
     *
     * @since 1.0
     */
    public static final class Builder {

        private final String scene;
        private boolean enabled = true;
        private Level level = Level.WARN;
        private List<String> channels = List.of();
        private String template;
        private List<String> receivers = List.of();
        private String receiversFrom;
        private Duration silentWindow;
        private int retry;

        private Builder(String scene) {
            this.scene = scene;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder level(Level level) {
            this.level = level;
            return this;
        }

        public Builder channels(List<String> channels) {
            this.channels = channels == null ? List.of() : channels;
            return this;
        }

        public Builder template(String template) {
            this.template = template;
            return this;
        }

        public Builder receivers(List<String> receivers) {
            this.receivers = receivers == null ? List.of() : receivers;
            return this;
        }

        public Builder receiversFrom(String receiversFrom) {
            this.receiversFrom = receiversFrom;
            return this;
        }

        public Builder silentWindow(Duration silentWindow) {
            this.silentWindow = silentWindow;
            return this;
        }

        public Builder retry(int retry) {
            this.retry = retry;
            return this;
        }

        public SceneConfig build() {
            return new SceneConfig(this);
        }
    }
}