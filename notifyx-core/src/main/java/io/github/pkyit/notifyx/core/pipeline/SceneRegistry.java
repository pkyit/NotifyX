package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * 场景注册表：持有一份不可变配置快照。
 *
 * <p>Admin 拉取到新配置时整体替换实例，而不是逐字段修改，保证读侧永远看到一致快照。</p>
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public final class SceneRegistry {

    private final Map<String, SceneConfig> scenes;
    private final SceneConfig defaults;

    private SceneRegistry(Map<String, SceneConfig> scenes, SceneConfig defaults) {
        this.scenes = Collections.unmodifiableMap(new LinkedHashMap<>(scenes));
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    /**
     * 创建注册表。
     *
     * @param scenes 场景配置
     * @param defaults 默认配置（未配置场景时使用）
     * @return 注册表
     */
    public static SceneRegistry of(Map<String, SceneConfig> scenes, SceneConfig defaults) {
        return new SceneRegistry(scenes, defaults);
    }

    /**
     * 查找场景配置；未配置时返回默认配置。
     *
     * @param scene 场景码
     * @return 场景配置，永不为 null
     */
    public SceneConfig find(String scene) {
        SceneConfig config = scenes.get(scene);
        if (config != null) {
            return config;
        }
        return SceneConfig.builder(scene)
                .enabled(true)
                .level(defaults.level())
                .channels(defaults.channels())
                .template(defaults.template())
                .receivers(defaults.receivers())
                .receiversFrom(defaults.receiversFrom())
                .silentWindow(defaults.silentWindow())
                .retry(defaults.retry())
                .build();
    }

    /**
     * 全部场景配置。
     *
     * @return 不可变 Map
     */
    public Map<String, SceneConfig> scenes() {
        return scenes;
    }

    public SceneConfig defaults() {
        return defaults;
    }

    /**
     * 便捷默认配置构建：WARN 级别、空通道、5 分钟静默。
     *
     * @return 默认场景配置
     */
    public static SceneConfig emptyDefaults() {
        return SceneConfig.builder("__defaults__")
                .level(Level.WARN)
                .channels(List.of())
                .silentWindow(Duration.ofMinutes(5))
                .build();
    }
}