package io.github.pkyit.notifyx.core.spi;

import java.util.Objects;

/**
 * 上下文属性的类型化键，避免 Map&lt;String,Object&gt; 的强制转换。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @param name 键名，用于诊断输出
 * @param type 值类型
 * @param <T> 值类型
 * @since 1.0
 */
public record ContextKey<T>(String name, Class<T> type) {

    /**
     * 规范化构造。
     *
     * @param name 键名
     * @param type 值类型
     */
    public ContextKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    /**
     * 创建类型化键。
     *
     * @param name 键名
     * @param type 值类型
     * @param <T> 值类型
     * @return 键
     */
    public static <T> ContextKey<T> of(String name, Class<T> type) {
        return new ContextKey<>(name, type);
    }
}