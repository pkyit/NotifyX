package io.github.pkyit.notifyx.core.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.pipeline.SceneConfig;

/**
 * 过滤器上下文。由管线在进入责任链前一次性算好，保证各过滤器看到同一份事实。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @param event 原始事件
 * @param sceneConfig 已解析的场景配置
 * @param now 本次处理的时刻
 * @param dedupeKey 已计算的指纹
 * @param silentWindow 已解析的静默期
 * @since 1.0
 */
public record FilterContext(NotifyEvent event, SceneConfig sceneConfig, Instant now,
                            String dedupeKey, Duration silentWindow) {

    /**
     * 规范化构造。
     *
     * @param event 事件
     * @param sceneConfig 场景配置
     * @param now 时刻
     * @param dedupeKey 指纹
     * @param silentWindow 静默期
     */
    public FilterContext {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(sceneConfig, "sceneConfig");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        Objects.requireNonNull(silentWindow, "silentWindow");
    }

    /**
     * 事件是否为 CRITICAL，漏斗旁路判定使用。
     *
     * @return 是否 CRITICAL
     */
    public boolean critical() {
        return event.level() == io.github.pkyit.notifyx.core.model.Level.CRITICAL;
    }
}
