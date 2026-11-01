package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.pipeline.InMemoryDeferredHandler;
import io.github.pkyit.notifyx.core.pipeline.MessageTemplate;
import io.github.pkyit.notifyx.core.pipeline.NotifyTaskExecutor;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.Degradable;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.spi.RateLimiter;
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

    @Test
    @DisplayName("fail-fast=true 且全部渠道禁用时启动失败")
    void shouldFailFastWhenAllChannelsDisabled() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDiagnostics().setFailFast(true);
        NotifyxDiagnostics diagnostics = new NotifyxDiagnostics(properties, List.of(new DisabledChannel()),
                new SimpleTemplateEngine(), Map.of(), new MemoryDedupeStore(), new MemoryRateLimiter(),
                new InMemoryFailureStore(), new LoggingAuditRecorder(), new DefaultMasker(),
                NotifyTaskExecutor.direct());

        assertThatThrownBy(diagnostics::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all NotifyChannel disabled");
    }

    @Test
    @DisplayName("fail-fast=false（默认）不因渠道禁用失败")
    void shouldNotFailFastByDefault() {
        NotifyxDiagnostics diagnostics = diagnostics(List.of(new DisabledChannel()));

        assertThatCode(diagnostics::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("配置 dedupe.store=jdbc 但实际内存时给出警告")
    void shouldWarnJdbcDedupeNotImplemented() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDedupe().setStore("jdbc");

        String table = diagnostics(properties, List.of(new StubChannel("log"))).render();

        assertThat(table).contains("jdbc 去重尚未实现");
    }

    @Test
    @DisplayName("配置 dedupe.store=redis 但实际内存时给出警告")
    void shouldWarnRedisStoreMismatch() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDedupe().setStore("redis");

        String table = diagnostics(properties, List.of(new StubChannel("log"))).render();

        assertThat(table).contains("配置 dedupe.store=redis，实际为内存实现");
    }

    @Test
    @DisplayName("配置 templates.engine 非 simple 但实际仍为 SimpleTemplateEngine 时给出警告")
    void shouldWarnTemplateEngineMismatch() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getTemplates().setEngine("freemarker");

        String table = diagnostics(properties, List.of(new StubChannel("log"))).render();

        assertThat(table).contains("templates.engine=freemarker");
        assertThat(table).contains("SimpleTemplateEngine");
    }

    @Test
    @DisplayName("Redis 降级包装在诊断表标记降级并降为 T0")
    void shouldShowDegradedRedisStore() {
        DedupeStore degraded = mock(DedupeStore.class, withSettings().extraInterfaces(Degradable.class));
        when(((Degradable) degraded).isDegraded()).thenReturn(true);
        NotifyxDiagnostics diagnostics = new NotifyxDiagnostics(new NotifyxProperties(),
                List.of(new StubChannel("log")), new SimpleTemplateEngine(), Map.of(), degraded,
                mock(RateLimiter.class), new InMemoryFailureStore(), new LoggingAuditRecorder(),
                new DefaultMasker(), NotifyTaskExecutor.direct());

        String table = diagnostics.render();

        assertThat(table).contains("Redis 降级中，当前使用内存实现");
        assertThat(table).contains("tier=T0");
    }

    @Test
    @DisplayName("fail-fast=true 且 dedupe.store=jdbc 未实现时启动失败")
    void shouldFailFastWhenJdbcDedupeNotImplemented() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDiagnostics().setFailFast(true);
        properties.getDedupe().setStore("jdbc");
        NotifyxDiagnostics diagnostics = diagnostics(properties, List.of(new StubChannel("log")));

        assertThatThrownBy(diagnostics::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dedupe.store=jdbc is not implemented");
    }

    @Test
    @DisplayName("fail-fast=true 且 templates.engine 与实际不符时启动失败")
    void shouldFailFastWhenTemplateEngineMismatch() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDiagnostics().setFailFast(true);
        properties.getTemplates().setEngine("freemarker");
        NotifyxDiagnostics diagnostics = diagnostics(properties, List.of(new StubChannel("log")));

        assertThatThrownBy(diagnostics::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("templates.engine=freemarker");
    }

    @Test
    @DisplayName("fail-fast=true 且 dedupe.store=redis 实际内存时启动失败")
    void shouldFailFastWhenRedisDedupeMismatch() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getDiagnostics().setFailFast(true);
        properties.getDedupe().setStore("redis");
        NotifyxDiagnostics diagnostics = diagnostics(properties, List.of(new StubChannel("log")));

        assertThatThrownBy(diagnostics::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dedupe.store=redis");
    }

    @Test
    @DisplayName("免打扰开启且 defer=true：诊断表展示延后投递器与容量")
    void shouldShowDeferredHandlerWhenQuietHoursEnabled() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getQuietHours().setEnabled(true);
        InMemoryDeferredHandler handler = new InMemoryDeferredHandler(
                new InMemoryDeferredHandler.Config(100), NotifyMetrics.noop());
        NotifyxDiagnostics diagnostics = new NotifyxDiagnostics(properties, List.of(new StubChannel("log")),
                new SimpleTemplateEngine(), Map.of(), new MemoryDedupeStore(), new MemoryRateLimiter(),
                new InMemoryFailureStore(), new LoggingAuditRecorder(), new DefaultMasker(),
                NotifyTaskExecutor.direct(), handler);

        String table = diagnostics.render();

        assertThat(table).contains("延后到窗口结束再发");
        assertThat(table).contains("InMemoryDeferredHandler");
        assertThat(table).contains("100");
        handler.close();
    }

    @Test
    @DisplayName("免打扰开启、defer=true 但没有延后投递器时给出警告")
    void shouldWarnWhenDeferEnabledButNoHandler() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getQuietHours().setEnabled(true);

        String table = diagnostics(properties, List.of(new StubChannel("log"))).render();

        assertThat(table).contains("没有 DeferredHandler 装配");
    }

    @Test
    @DisplayName("免打扰开启、defer=false 时明确提示直接丢弃")
    void shouldShowDropModeWhenDeferDisabled() {
        NotifyxProperties properties = new NotifyxProperties();
        properties.getQuietHours().setEnabled(true);
        properties.getQuietHours().setDefer(false);

        String table = diagnostics(properties, List.of(new StubChannel("log"))).render();

        assertThat(table).contains("defer=false，命中事件直接丢弃");
    }

    private static NotifyxDiagnostics diagnostics(List<NotifyChannel> channels) {
        return diagnostics(new NotifyxProperties(), channels);
    }

    private static NotifyxDiagnostics diagnostics(NotifyxProperties properties, List<NotifyChannel> channels) {
        return new NotifyxDiagnostics(properties, channels, new SimpleTemplateEngine(), Map.of(),
                new MemoryDedupeStore(), new MemoryRateLimiter(), new InMemoryFailureStore(),
                new LoggingAuditRecorder(), new DefaultMasker(), NotifyTaskExecutor.direct());
    }

    private static final class DisabledChannel implements NotifyChannel {

        @Override
        public String name() {
            return "disabled";
        }

        @Override
        public boolean isEnabled(NotifyContext ctx) {
            return false;
        }

        @Override
        public SendResult send(NotifyContext ctx) {
            return SendResult.success("1", null, Duration.ZERO);
        }
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
