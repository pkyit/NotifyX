package io.github.pkyit.notifyx.core.spi;

/**
 * 可报告降级状态的 SPI 实现。
 *
 * <p>用于 Redis 等外部存储的“降级到内存”包装：健康检查与启动诊断通过该接口判断
 * 当前是否处于降级期，而不是只看实现类名。</p>
 *
 * <p>@implNote 实现必须线程安全，且 {@link #isDegraded()} 不得抛异常。</p>
 *
 * @since 1.0
 */
public interface Degradable {

    /**
     * 当前是否处于降级状态。
     *
     * @return true 表示主实现不可用、正在使用备用实现
     */
    boolean isDegraded();
}
