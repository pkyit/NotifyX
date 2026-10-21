package io.github.pkyit.notifyx.core.spi;

import java.time.Duration;

/**
 * 去重存储 SPI：窗口内同一指纹只放行一次。
 *
 * <p>{@link #release(String)} 是必需能力：发送最终失败后必须释放槽位，否则故障期间同类
 * 事件会被永久静默（设计书 7.4）。</p>
 *
 * <p>@implNote 实现类必须线程安全；{@link #tryAcquire} 必须是原子操作。</p>
 *
 * @since 1.0
 */
public interface DedupeStore {

    /**
     * 原子地「检查并占位」。
     *
     * @param dedupeKey 指纹
     * @param window 窗口
     * @return true=首次出现放行；false=窗口内已存在
     */
    boolean tryAcquire(String dedupeKey, Duration window);

    /**
     * 释放槽位。
     *
     * @param dedupeKey 指纹
     */
    void release(String dedupeKey);

    /**
     * 当前条目数，诊断用。
     *
     * @return 条目数
     */
    long size();
}
