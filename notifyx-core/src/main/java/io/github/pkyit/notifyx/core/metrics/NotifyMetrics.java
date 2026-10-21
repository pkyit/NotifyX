package io.github.pkyit.notifyx.core.metrics;

import java.time.Duration;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;

/**
 * 指标出口。默认全部 no-op，保证零依赖可跑；Spring 环境下由 Micrometer 实现替换。
 *
 * <p>严禁把 receiver / bizId / eventId 作为标签，避免 Prometheus 时序爆炸（设计书 15.1）。</p>
 *
 * <p>@implNote 实现类必须线程安全，且不得抛异常。</p>
 *
 * @since 1.0
 */
public interface NotifyMetrics {

    /**
     * 事件进入管线。
     *
     * @param event 事件
     * @param source 接入来源（L0-L4）
     */
    default void eventReceived(NotifyEvent event, String source) {
    }

    /**
     * 一次投递结果。
     *
     * @param channel 渠道
     * @param scene 场景
     * @param status 状态
     * @param cost 耗时
     */
    default void sendResult(String channel, String scene, SendResult.Status status, Duration cost) {
    }

    /**
     * 队列丢弃。
     *
     * @param reason 原因（queue-full / replaced-by-higher-priority）
     * @param level 级别
     */
    default void queueDiscarded(String reason, Level level) {
    }

    /**
     * 过滤器拦截。
     *
     * @param filter 过滤器名
     * @param scene 场景
     */
    default void filterRejected(String filter, String scene) {
    }

    /**
     * no-op 实现。
     *
     * @return 指标出口
     */
    static NotifyMetrics noop() {
        return new NotifyMetrics() {
        };
    }
}
