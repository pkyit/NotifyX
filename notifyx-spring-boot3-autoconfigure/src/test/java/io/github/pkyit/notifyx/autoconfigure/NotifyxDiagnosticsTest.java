package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.pipeline.MessageTemplate;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.support.DefaultMasker;
import io.github.pkyit.notifyx.core.support.InMemoryFailureStore;
import io.github.pkyit.notifyx.core.support.LoggingAuditRecorder;
import io.github.pkyit.notifyx.core.support.MemoryDedupeStore;
import io.github.pkyit.notifyx.core.support.MemoryRateLimiter;
import io.github.pkyit.notifyx.core.support.SimpleTemplateEngine;

/**
 * 启动诊断表测试（设计书 14.4 第 6 条）：内容完整、不泄露配置值、降级有提示。
 *
 * @since 1.0
 */
class NotifyxDiagnosticsTest {

    @Test
    @DisplayName("诊断表包含版本/模式/渠道/SPI/降级提示")
    void shouldRenderCapabilityTable() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.setMode("local");
        NotifyxDiagnostics diagnostics = new NotifyxDiagnostics(
                properties, List.of(new StubChannel("log"), new StubChannel("email")),
                new SimpleTemplateEngine(), Map.of("default", new MessageTemplate("default", "t", "c")),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore(),
                new LoggingAuditRecorder(), new DefaultMasker(), NotifyTaskExecutor.direct());

        String table = diagnostics.render();

        assertThat(table).contains("NotifyX", "mode=local", "tier=T0");
        assertThat(table).contains("渠道（2 启用 / 2 已注册）");
        assertThat(table).contains("log", "email");
        assertThat(table).contains("去重", "MemoryDedupeStore", "单机内存");
        assertThat(table).contains("模板：1 个", "引擎：simple");
        assertThat(table).contains("免打扰未启用");
    }

    @Test
    @DisplayName("没有渠道时显式告警")
    void shouldWarnWhenNoChannel() {
        NotifyxDiagnostics diagnostics = diagnostics(List.of());

        assertThat(diagnostics.render()).contains("没有任何渠道");
    }

    @Test
    @DisplayName("诊断表不包含配置值（密码/secret）")
    void shouldNotLeakSecrets() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getChannels().getEmail().setPassword("super-secret-password");
        NotifyxDiagnostics diagnostics = new NotifyxDiagnostics(
                properties, List.of(new StubChannel("log")), new SimpleTemplateEngine(), Map.of(),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore(),
                new LoggingAuditRecorder(), new DefaultMasker(), NotifyTaskExecutor.direct());

        assertThat(diagnostics.render()).doesNotContain("super-secret-password");
    }

    @Test
    @DisplayName("banner=false 时不打印（由监听器判断，渲染方法仍可调用）")
    void shouldRespectBannerSwitch() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDiagnostics().setBanner(false);
        NotifyxDiagnostics diagnostics = new NotifyxDiagnostics(
                properties, List.of(new StubChannel("log")), new SimpleTemplateEngine(), Map.of(),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore(),
                new LoggingAuditRecorder(), new DefaultMasker(), NotifyTaskExecutor.direct());

        // 监听器在 banner=false 时直接返回；这里验证配置可被读取
        assertThat(properties.getDiagnostics().isBanner()).isFalse();
        assertThat(diagnostics.render()).isNotBlank();
    }

    private static NotifyxDiagnostics diagnostics(List<NotifyChannel> channels) {
        return new NotifyxDiagnostics(new NotifyxProperties(), channels, new SimpleTemplateEngine(), Map.of(),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore(),
                new LoggingAuditRecorder(), new DefaultMasker(), NotifyTaskExecutor.direct());
    }

    private static final class StubChannel implements NotifyChannel {

        private final String name;

        private StubChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            return SendResult.success("1", null, Duration.ZERO);
        }
    }
}
