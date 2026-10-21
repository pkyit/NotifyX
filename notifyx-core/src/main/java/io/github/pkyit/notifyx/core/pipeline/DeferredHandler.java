package io.github.pkyit.notifyx.core.pipeline;

import java.time.Instant;

import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 延后投递处理器。免打扰时段把事件推迟到工作时间，由外部调度器实现。
 *
 * <p>@implNote 实现类必须线程安全。</p>
 *
 * @since 1.0
 */
@FunctionalInterface
public interface DeferredHandler {

    /**
     * 安排一次延后投递。
     *
     * @param event 事件
     * @param until 目标时间
     */
    void defer(NotifyEvent event, Instant until);
}
