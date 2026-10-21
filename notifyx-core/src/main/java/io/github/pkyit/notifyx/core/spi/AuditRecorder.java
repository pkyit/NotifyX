package io.github.pkyit.notifyx.core.spi;

/**
 * 审计记录 SPI。默认实现只记日志（no-op 落库），保证零依赖可跑。
 *
 * <p>@implNote 实现类必须线程安全，且不得抛异常影响主流程。</p>
 *
 * @since 1.0
 */
public interface AuditRecorder {

    /**
     * 记录一条审计。
     *
     * @param record 审计记录
     */
    void record(AuditRecord record);

    /**
     * 释放资源（批量刷盘）。
     */
    default void close() {
    }
}