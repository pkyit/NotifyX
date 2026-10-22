package io.github.pkyit.notifyx.core.pipeline;

import java.util.function.Consumer;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 聚合出口抽象（设计书 §9.5）：T0 内存实现与 T1 Redis 实现共用。
 *
 * <p>管线只在「去重命中」时调用 {@link #record}，实现负责在窗口结束时通过
 * {@link #sink(Consumer)} 投递一条摘要事件。</p>
 *
 * <p>@implNote 实现必须线程安全，且 {@link #record} 不得向调用方抛异常（聚合是旁路）。</p>
 *
 * @since 1.0
 */
public interface EventAggregator extends AutoCloseable {

    /**
     * 设置摘要出口。
     *
     * @param sink 摘要消费者
     */
    void sink(Consumer<NotifyEvent> sink);

    /**
     * 记录一条被去重的事件。
     *
     * @param event 原始事件
     * @param level 生效级别
     * @param dedupeKey 去重指纹
     * @param instanceId 当前实例
     */
    void record(NotifyEvent event, Level level, String dedupeKey, String instanceId);

    /**
     * 立即汇总所有未结束的窗口（关闭时调用）。
     */
    void flushAll();

    @Override
    void close();
}
