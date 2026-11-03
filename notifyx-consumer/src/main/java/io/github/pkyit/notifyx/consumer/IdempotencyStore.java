package io.github.pkyit.notifyx.consumer;

/**
 * consumer 幂等 SPI。
 *
 * @since 1.0
 */
public interface IdempotencyStore {

    /**
     * 首次出现返回 true；重复（TTL 内）返回 false。
     *
     * @param key 幂等键（通常为 NotifyEnvelope.idempotencyKey）
     * @return 是否首次出现
     */
    boolean firstSeen(String key);

    /**
     * 释放一个已标记的幂等键，使该键可被再次处理。
     *
     * <p>用于消费失败进入退避重试（死信 + 多级 TTL，设计书 §11.2）时：失败的消息在
     * 重投前需释放幂等键，否则重投会被当作重复消息丢弃。默认空实现，不强制所有存储支持。</p>
     *
     * @param key 幂等键
     * @since 1.0.1
     */
    default void release(String key) {
    }
}
