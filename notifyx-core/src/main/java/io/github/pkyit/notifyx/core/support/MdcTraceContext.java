package io.github.pkyit.notifyx.core.support;

import org.slf4j.MDC;

import io.github.pkyit.notifyx.core.spi.TraceContext;

/**
 * 基于 SLF4J MDC 的默认实现（设计书 15.3）。
 *
 * <p>Spring Boot 的 Micrometer Tracing 会把 <code>traceId</code>/<code>spanId</code> 写入 MDC，
 * SkyWalking 等探针也遵循同样约定，因此读 MDC 即可覆盖主流链路追踪方案，core 无需新增依赖。</p>
 *
 * <p>@implNote 无状态（只读 MDC），线程安全。</p>
 *
 * @since 1.0
 */
public final class MdcTraceContext implements TraceContext {

    /** 默认 traceId 的 MDC key。 */
    public static final String DEFAULT_TRACE_KEY = "traceId";

    /** 默认 spanId 的 MDC key。 */
    public static final String DEFAULT_SPAN_KEY = "spanId";

    private final String traceKey;
    private final String spanKey;

    /**
     * 使用默认 key 构造。
     */
    public MdcTraceContext() {
        this(DEFAULT_TRACE_KEY, DEFAULT_SPAN_KEY);
    }

    /**
     * 自定义 MDC key，适配非标准命名的探针。
     *
     * @param traceKey traceId 的 MDC key
     * @param spanKey spanId 的 MDC key
     */
    public MdcTraceContext(String traceKey, String spanKey) {
        this.traceKey = traceKey == null || traceKey.isBlank() ? DEFAULT_TRACE_KEY : traceKey;
        this.spanKey = spanKey == null || spanKey.isBlank() ? DEFAULT_SPAN_KEY : spanKey;
    }

    @Override
    public String traceId() {
        return value(MDC.get(traceKey));
    }

    @Override
    public String spanId() {
        return value(MDC.get(spanKey));
    }

    private static String value(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
