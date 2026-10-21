package io.github.pkyit.notifyx.core.spi;

/**
 * Trace 上下文读取口（设计书 15.3）。
 *
 * <p>core 只依赖 SLF4J，因此默认实现直接读 MDC；框架不硬依赖 Micrometer Tracing。
 * 有 tracing 能力时，Micrometer 会把 traceId/spanId 写进 MDC，这里自然读到。</p>
 *
 * <p>@implNote 实现必须线程安全；每次调用都可能返回不同值（MDC 是线程绑定的）。</p>
 *
 * @since 1.0
 */
public interface TraceContext {

    /**
     * 当前 traceId。
     *
     * @return traceId，缺失时返回 null
     */
    String traceId();

    /**
     * 当前 spanId。
     *
     * @return spanId，缺失时返回 null
     */
    String spanId();

    /**
     * 空实现：不携带任何 trace 信息。
     *
     * @return 空实现
     */
    static TraceContext none() {
        return Noop.INSTANCE;
    }

    /**
     * 空实现的单例。
     *
     * @since 1.0
     */
    enum Noop implements TraceContext {

        /** 单例。 */
        INSTANCE;

        @Override
        public String traceId() {
            return null;
        }

        @Override
        public String spanId() {
            return null;
        }
    }
}
