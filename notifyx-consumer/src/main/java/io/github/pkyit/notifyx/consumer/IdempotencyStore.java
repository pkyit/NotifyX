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
}
