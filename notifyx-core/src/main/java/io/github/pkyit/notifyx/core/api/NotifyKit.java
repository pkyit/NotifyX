package io.github.pkyit.notifyx.core.api;

import java.util.Map;

import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 用户唯一需要认识的接口。方法数刻意控制在 6 个，防止 API 膨胀（设计书 6.7）。
 *
 * <p>@implNote 实现类必须线程安全。</p>
 *
 * @since 1.0
 */
public interface NotifyKit {

    /**
     * 全量控制：直接提交一个已构建的事件。
     *
     * @param event 事件
     */
    void send(NotifyEvent event);

    /**
     * 场景码 + 参数，接收人走配置。
     *
     * @param scene 场景码
     * @param params 模板变量
     */
    void send(String scene, Map<String, ?> params);

    /**
     * 场景码 + 指定接收人 + 参数。
     *
     * @param scene 场景码
     * @param receiver 接收人
     * @param params 模板变量
     */
    void send(String scene, String receiver, Map<String, ?> params);

    /**
     * 异常直抛：自动抓堆栈、自动定级 ERROR、场景取 {@code UNCAUGHT_EXCEPTION}。
     *
     * @param throwable 异常
     */
    void alarm(Throwable throwable);

    /**
     * 指定场景的异常告警，context 按设计书 8.5 的规则摊平进 params。
     *
     * @param scene 场景码
     * @param throwable 异常
     * @param context 上下文元素
     */
    void alarm(String scene, Throwable throwable, Object... context);

    /**
     * 显式异步提交。
     *
     * @param event 事件
     */
    void sendAsync(NotifyEvent event);
}
