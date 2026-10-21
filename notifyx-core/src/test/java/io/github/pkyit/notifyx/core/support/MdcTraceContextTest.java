package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.github.pkyit.notifyx.core.spi.TraceContext;

/**
 * MDC trace 读取测试（设计书 15.3）。
 *
 * @since 1.0
 */
class MdcTraceContextTest {

    private final TraceContext context = new MdcTraceContext();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("从 MDC 读取 traceId / spanId")
    void shouldReadFromMdc() {
        MDC.put("traceId", "trace-abc");
        MDC.put("spanId", "span-123");

        assertThat(context.traceId()).isEqualTo("trace-abc");
        assertThat(context.spanId()).isEqualTo("span-123");
    }

    @Test
    @DisplayName("缺失或空白的值为 null")
    void shouldReturnNullWhenAbsent() {
        assertThat(context.traceId()).isNull();
        assertThat(context.spanId()).isNull();

        MDC.put("traceId", "   ");
        assertThat(context.traceId()).isNull();
    }

    @Test
    @DisplayName("自定义 MDC key 适配非标准探针")
    void shouldSupportCustomKeys() {
        TraceContext custom = new MdcTraceContext("trace_id", "span_id");
        MDC.put("trace_id", "skywalking-trace");
        MDC.put("span_id", "skywalking-span");

        assertThat(custom.traceId()).isEqualTo("skywalking-trace");
        assertThat(custom.spanId()).isEqualTo("skywalking-span");
    }

    @Test
    @DisplayName("TraceContext.none() 永远返回 null")
    void noopShouldReturnNull() {
        MDC.put("traceId", "ignored");
        assertThat(TraceContext.none().traceId()).isNull();
        assertThat(TraceContext.none().spanId()).isNull();
    }
}
