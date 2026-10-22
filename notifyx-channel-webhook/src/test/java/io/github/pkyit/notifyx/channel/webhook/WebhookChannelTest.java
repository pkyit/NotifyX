package io.github.pkyit.notifyx.channel.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.exception.ChannelProtocolException;
import io.github.pkyit.notifyx.core.exception.ChannelRateLimitException;
import io.github.pkyit.notifyx.core.exception.ChannelTimeoutException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * Webhook 渠道契约测试（设计书 §18.3 / §10.4）。
 *
 * <p>用 JDK 自带 HttpServer 起一个真实 HTTP 端点，验证「签名、报文、状态码分类、白名单、超时」
 * 这些一旦改错就会静默失安全性的行为。不引入 WireMock，避免测试依赖膨胀。</p>
 *
 * @since 1.0
 */
class WebhookChannelTest {

    private static final String SECRET = "s3cret-key";

    private final AtomicReference<Headers> capturedHeaders = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "ok";
    private volatile long responseDelayMillis;

    private HttpServer server;
    private String url;
    private WebhookChannelProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        properties = new WebhookChannelProperties();
        properties.setUrl(url);
        properties.setSecret(SECRET);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("发送 JSON 报文 + HMAC-SHA256 签名 + 幂等键")
    void shouldSendSignedPayload() throws Exception {
        WebhookChannel channel = new WebhookChannel(properties);
        NotifyContext ctx = context();

        SendResult result = channel.send(ctx);

        assertThat(result.status()).isEqualTo(SendResult.Status.SUCCESS);
        assertThat(result.messageId()).isEqualTo("200");
        assertThat(result.cost()).isGreaterThanOrEqualTo(Duration.ZERO);

        assertThat(capturedMethod.get()).isEqualTo("POST");
        Headers headers = capturedHeaders.get();
        assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json; charset=utf-8");
        assertThat(headers.getFirst("User-Agent")).isEqualTo("NotifyX/1.0");
        assertThat(headers.getFirst("X-Notifyx-Event-Id")).isEqualTo(ctx.event().eventId());
        assertThat(headers.getFirst("X-Notifyx-Delivery-Attempt")).isEqualTo("2");
        assertThat(headers.getFirst("X-Notifyx-Idempotency-Key")).isEqualTo("key-1");
        assertThat(headers.getFirst("X-Notifyx-Timestamp")).matches("\\d{10}");

        String timestamp = headers.getFirst("X-Notifyx-Timestamp");
        String signature = headers.getFirst("X-Notifyx-Signature");
        assertThat(signature).isEqualTo("sha256=" + hmacHex(timestamp + "." + capturedBody.get(), SECRET));

        String body = capturedBody.get();
        assertThat(body).contains("\"scene\":\"ORDER_CREATE_FAIL\"");
        assertThat(body).contains("\"level\":\"ERROR\"");
        assertThat(body).contains("\"title\":\"【订单异常】SO1\"");
        assertThat(body).contains("\"receiver\":\"ops@example.com\"");
        assertThat(body).contains("\"attempt\":2");
        assertThat(body).contains("\"params\":");
        assertThat(body).contains("\\\"不足\\\"").contains("\\n第二行");
    }

    @Test
    @DisplayName("sign-enabled=false 时不带签名头（内网调试场景）")
    void shouldSkipSignatureWhenDisabled() throws Exception {
        properties.setSignEnabled(false);

        new WebhookChannel(properties).send(context());

        assertThat(capturedHeaders.get().getFirst("X-Notifyx-Signature")).isNull();
    }

    @Test
    @DisplayName("自定义 header 透传")
    void shouldSendCustomHeaders() throws Exception {
        properties.getHeaders().put("X-Tenant", "acme");

        new WebhookChannel(properties).send(context());

        assertThat(capturedHeaders.get().getFirst("X-Tenant")).isEqualTo("acme");
    }

    @Test
    @DisplayName("429 → ChannelRateLimitException（可重试长退避）")
    void shouldMapRateLimit() {
        responseStatus = 429;

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelRateLimitException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("5xx → ChannelException（可重试）")
    void shouldMapServerError() {
        responseStatus = 503;

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelException.class)
                .isNotInstanceOf(ChannelProtocolException.class);
    }

    @Test
    @DisplayName("4xx → ChannelProtocolException（不可重试）")
    void shouldMapClientError() {
        responseStatus = 403;

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("expect-status 可配置：202 视为成功")
    void shouldAcceptConfiguredStatus() throws Exception {
        responseStatus = 202;
        properties.setExpectStatus(List.of(202));

        SendResult result = new WebhookChannel(properties).send(context());

        assertThat(result.status()).isEqualTo(SendResult.Status.SUCCESS);
        assertThat(result.messageId()).isEqualTo("202");
    }

    @Test
    @DisplayName("host 不在白名单 → 拒绝发送")
    void shouldRejectHostOutsideAllowList() {
        properties.setAllowedHosts(List.of("hooks.example.com"));

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("allow-list");
    }

    @Test
    @DisplayName("url 为空 → 拒绝发送")
    void shouldRejectBlankUrl() {
        properties.setUrl("  ");

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("url is blank");
    }

    @Test
    @DisplayName("读超时 → ChannelTimeoutException（可重试）")
    void shouldTimeout() {
        responseDelayMillis = 1000;
        properties.setReadTimeout(Duration.ofMillis(200));

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelTimeoutException.class);
    }

    @Test
    @DisplayName("响应体超过 1KB 时截断，避免把大响应写进失败记录")
    void shouldTruncateLongResponse() throws Exception {
        responseBody = "x".repeat(2000);

        SendResult result = new WebhookChannel(properties).send(context());

        assertThat(result.rawResponse()).hasSize(SendResult.MAX_RAW_RESPONSE_LENGTH);
    }

    @Test
    @DisplayName("无接收人/无 tags 时仍生成合法 JSON（null 与空对象）")
    void shouldRenderNullsAndEmptyMaps() throws Exception {
        NotifyEvent event = NotifyEvent.builder("SCENE_A").build();
        NotifyContext ctx = NotifyContext.builder(event).renderedTitle("t").renderedContent("c").build();

        new WebhookChannel(properties).send(ctx);

        assertThat(capturedBody.get()).contains("\"receiver\":null").contains("\"tags\":{}");
    }

    @Test
    @DisplayName("嵌套 Map / List 按 JSON 结构输出，而不是 String.valueOf")
    void shouldRenderNestedJson() throws Exception {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", 42);
        user.put("roles", List.of("admin", "ops"));
        user.put("active", true);
        NotifyEvent event = NotifyEvent.builder("SCENE_A")
                .param("user", user)
                .param("scores", List.of(1, 2))
                .build();
        NotifyContext ctx = NotifyContext.builder(event).renderedTitle("t").renderedContent("c").build();

        new WebhookChannel(properties).send(ctx);

        assertThat(capturedBody.get())
                .contains("\"user\":{\"id\":42,\"roles\":[\"admin\",\"ops\"],\"active\":true}")
                .contains("\"scores\":[1,2]");
    }

    @Test
    @DisplayName("业务自定义 header 不能覆盖框架保留 header")
    void shouldNotAllowReservedHeaderOverride() throws Exception {
        properties.getHeaders().put("Content-Type", "text/plain");
        properties.getHeaders().put("X-Notifyx-Signature", "forged");
        properties.getHeaders().put("X-Notifyx-Event-Id", "forged");
        properties.getHeaders().put("X-Tenant", "acme");
        NotifyContext ctx = context();

        new WebhookChannel(properties).send(ctx);

        Headers headers = capturedHeaders.get();
        assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json; charset=utf-8");
        assertThat(headers.getFirst("X-Notifyx-Event-Id")).isEqualTo(ctx.event().eventId());
        assertThat(headers.getFirst("X-Tenant")).isEqualTo("acme");
        String timestamp = headers.getFirst("X-Notifyx-Timestamp");
        assertThat(headers.getFirst("X-Notifyx-Signature"))
                .isEqualTo("sha256=" + hmacHex(timestamp + "." + capturedBody.get(), SECRET))
                .isNotEqualTo("forged");
    }

    @Test
    @DisplayName("非法 URL → ChannelProtocolException，而不是 IllegalArgumentException")
    void shouldRejectMalformedUrl() {
        properties.setUrl("http://exa mple.com/hook");

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("invalid webhook url");
    }

    @Test
    @DisplayName("非 http/https scheme → ChannelProtocolException")
    void shouldRejectUnsupportedScheme() {
        properties.setUrl("file:///tmp/hook");

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("unsupported webhook scheme");
    }

    @Test
    @DisplayName("非法 header 值 → ChannelProtocolException，而不是 IllegalArgumentException")
    void shouldRejectMalformedHeader() {
        properties.getHeaders().put("X-Bad", "line1\nline2");

        assertThatThrownBy(() -> new WebhookChannel(properties).send(context()))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("invalid webhook request");
    }

    private NotifyContext context() {
        NotifyEvent event = NotifyEvent.builder("ORDER_CREATE_FAIL")
                .level(Level.ERROR)
                .bizId("SO1")
                .title("【订单异常】SO1")
                .param("reason", "库存 \"不足\"\n第二行")
                .build();
        return NotifyContext.builder(event)
                .renderedTitle("【订单异常】SO1")
                .renderedContent("订单 SO1 创建失败")
                .receiver("ops@example.com")
                .attempt(2)
                .idempotencyKey("key-1")
                .build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        capturedMethod.set(exchange.getRequestMethod());
        capturedHeaders.set(exchange.getRequestHeaders());
        capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String hmacHex(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
