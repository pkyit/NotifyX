package io.github.pkyit.notifyx.core.spi;

import io.github.pkyit.notifyx.core.exception.BusException;

/**
 * 传输总线 SPI（设计书 §11.1）：{@code notifyx.mode=mq} 时启用。
 *
 * <p>实现必须保证：失败时抛异常（由 SDK 落盘兜底），不能静默吞掉。</p>
 *
 * <p>@implNote 实现需线程安全；{@link #close()} 幂等。</p>
 *
 * @since 1.0
 */
public interface NotifyBus extends NotifyxOrdered, AutoCloseable {

    /**
     * 总线名。
     *
     * @return kafka / rabbitmq 等
     */
    String name();

    /**
     * 发布一条报文。
     *
     * @param envelope 报文
     * @throws BusException 发布失败
     */
    void publish(io.github.pkyit.notifyx.core.model.NotifyEnvelope envelope) throws BusException;

    /**
     * 批量发布。
     *
     * @param envelopes 报文列表
     * @throws BusException 发布失败
     */
    default void publishBatch(java.util.List<io.github.pkyit.notifyx.core.model.NotifyEnvelope> envelopes)
            throws BusException {
        for (io.github.pkyit.notifyx.core.model.NotifyEnvelope envelope : envelopes) {
            publish(envelope);
        }
    }

    @Override
    default void close() {
    }
}
