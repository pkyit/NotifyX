package io.github.pkyit.notifyx.core;

/**
 * 告警通道扩展点（SPI）。
 *
 * <p>这是整个 starter 唯一的“输出”契约：任何投递方式（日志、Webhook、钉钉、飞书、
 * 邮件、短信、消息队列……）只要实现本接口并注册为 Bean，就会被自动发现并接入调度，
 * 核心代码无需任何修改。</p>
 *
 * <p>实现类必须是无状态的、线程安全的；{@link #send(AlertEvent)} 会被调度线程并发调用。</p>
 */
public interface AlertChannel {

    /**
     * 通道唯一名称，用于日志、指标与配置引用。
     */
    String name();

    /**
     * 是否处理该事件。默认全部处理；通道可以按级别、来源、标签自行裁剪。
     */
    default boolean supports(AlertEvent event) {
        return true;
    }

    /**
     * 投递事件。允许抛出异常：调度器会隔离单个通道的失败，不影响其它通道，也不会影响业务线程。
     */
    void send(AlertEvent event) throws Exception;

    /**
     * 同一事件内多个通道的执行顺序，值越小越先执行。
     */
    default int order() {
        return 0;
    }
}
