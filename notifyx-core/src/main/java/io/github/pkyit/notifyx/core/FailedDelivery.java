package io.github.pkyit.notifyx.core;

/**
 * 一次失败的通道投递记录，交给失败回调处理（例如打点、落库、转入重试队列）。
 *
 * @param channel 通道名称
 * @param event   原始事件
 * @param cause   通道抛出的异常
 */
public record FailedDelivery(String channel, AlertEvent event, Throwable cause) {
}
