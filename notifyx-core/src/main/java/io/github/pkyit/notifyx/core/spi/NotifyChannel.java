package io.github.pkyit.notifyx.core.spi;

import java.time.Duration;
import java.util.Set;

import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.model.HealthStatus;
import io.github.pkyit.notifyx.core.model.RenderFormat;
import io.github.pkyit.notifyx.core.model.SendResult;

/**
 * 通知渠道 SPI：新增渠道 = 实现本接口 + 一个自动配置类。
 *
 * <p>实现应保持极薄：打包报文 → 签名 → 发送 → 解析响应。渲染已在管线内完成（ADR-004）。</p>
 *
 * <p>@implNote 实现类必须线程安全：{@link #send(NotifyContext)} 会被多条投递线程并发调用。</p>
 *
 * @since 1.0
 */
public interface NotifyChannel extends NotifyxOrdered {

    /**
     * 渠道标识，全局唯一，如 {@code email} / {@code dingtalk}。
     *
     * @return 渠道名
     */
    String name();

    /**
     * 运行时开关，可做灰度、租户、环境判断；返回 false 时静默跳过。
     *
     * @param ctx 执行上下文
     * @return 是否启用
     */
    default boolean isEnabled(NotifyContext ctx) {
        return true;
    }

    /**
     * 真正发送。
     *
     * @param ctx 执行上下文，已包含渲染结果
     * @return 投递结果
     * @throws ChannelException 渠道侧失败，异常子类决定重试策略
     */
    SendResult send(NotifyContext ctx) throws ChannelException;

    /**
     * 声明偏好的渲染格式，管线据此渲染。
     *
     * @return 渲染格式
     */
    default RenderFormat preferredFormat() {
        return RenderFormat.PLAIN;
    }

    /**
     * 最大尝试次数（含首次）。短信渠道应返回 1，避免重复计费。
     *
     * @return 最大尝试次数
     */
    default int maxRetries() {
        return 3;
    }

    /**
     * 重试退避基数。
     *
     * @return 退避基数
     */
    default Duration retryBackoff() {
        return Duration.ofSeconds(2);
    }

    /**
     * 明确不可重试的异常类型。
     *
     * @return 异常类型集合
     */
    default Set<Class<? extends Throwable>> nonRetryable() {
        return Set.of();
    }

    /**
     * 是否支持批量接收人；true 时管线不 fan-out。
     *
     * @return 是否支持批量
     */
    default boolean supportsBatch() {
        return false;
    }

    /**
     * 健康探测；默认不实现，避免探活产生真实流量。
     *
     * @return 健康状态
     */
    default HealthStatus health() {
        return HealthStatus.UNKNOWN;
    }

    /**
     * 释放资源（连接池、token 缓存）。
     */
    default void close() {
    }
}
