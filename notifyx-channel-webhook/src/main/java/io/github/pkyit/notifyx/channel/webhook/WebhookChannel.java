package io.github.pkyit.notifyx.channel.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.StringJoiner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.exception.ChannelProtocolException;
import io.github.pkyit.notifyx.core.exception.ChannelRateLimitException;
import io.github.pkyit.notifyx.core.exception.ChannelTimeoutException;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * 通用 HTTP Webhook 渠道。
 *
 * <p>签名规范：{@code HMAC-SHA256(timestamp + "." + rawBody, secret)}，十六进制小写，
 * 放在 {@code X-Notifyx-Signature}；同时带时间戳用于接收方防重放（设计书 10.4）。</p>
 *
 * <p>@implNote 线程安全；{@link HttpClient} 本身线程安全且复用连接池。</p>
 *
 * @since 1.0
 */
public class WebhookChannel implements NotifyChannel {

    /** 渠道名。 */
    public static final String NAME = "webhook";

    private static final String USER_AGENT = "NotifyX/1.0";

    private final WebhookChannelProperties properties;
    private final HttpClient client;

    /**
     * 构造渠道。
     *
     * @param properties 配置
     */
    public WebhookChannel(WebhookChannelProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SendResult send(NotifyContext ctx) throws ChannelException {
        long started = System.nanoTime();
        String body = payload(ctx);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(verifyUri(properties.getUrl()))
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .header("X-Notifyx-Event-Id", ctx.event().eventId())
                .header("X-Notifyx-Delivery-Attempt", String.valueOf(ctx.attempt()))
                .header("X-Notifyx-Timestamp", timestamp)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (ctx.idempotencyKey() != null) {
            request.header("X-Notifyx-Idempotency-Key", ctx.idempotencyKey());
        }
        if (properties.isSignEnabled() && properties.getSecret() != null) {
            request.header("X-Notifyx-Signature", "sha256=" + sign(timestamp + "." + body, properties.getSecret()));
        }
        properties.getHeaders().forEach(request::header);
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Duration cost = Duration.ofNanos(System.nanoTime() - started);
            return classify(response, cost);
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new ChannelTimeoutException(NAME, ex);
        } catch (IOException ex) {
            throw new ChannelTimeoutException(NAME, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ChannelException(NAME, "interrupted while sending webhook", ex);
        }
    }

    private SendResult classify(HttpResponse<String> response, Duration cost) throws ChannelException {
        int status = response.statusCode();
        if (properties.getExpectStatus().contains(status)) {
            return SendResult.success(String.valueOf(status), truncate(response.body()), cost);
        }
        if (status == 429) {
            throw new ChannelRateLimitException(NAME, "webhook rate limited (429): " + response.body());
        }
        if (status >= 500) {
            throw new ChannelException(NAME, "webhook server error " + status + ": " + truncate(response.body()));
        }
        throw new ChannelProtocolException(NAME, "webhook rejected " + status + ": " + truncate(response.body()));
    }

    private URI verifyUri(String rawUrl) throws ChannelProtocolException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ChannelProtocolException(NAME, "notifyx.channels.webhook.url is blank");
        }
        URI uri = URI.create(rawUrl);
        if (!properties.getAllowedHosts().isEmpty() && !properties.getAllowedHosts().contains(uri.getHost())) {
            throw new ChannelProtocolException(NAME, "host not in allow-list: " + uri.getHost());
        }
        return uri;
    }

    private String payload(NotifyContext ctx) {
        StringBuilder builder = new StringBuilder(256);
        builder.append('{');
        field(builder, "eventId", ctx.event().eventId()).append(',');
        field(builder, "scene", ctx.event().scene()).append(',');
        field(builder, "level", ctx.event().level().name()).append(',');
        field(builder, "title", ctx.renderedTitle()).append(',');
        field(builder, "content", ctx.renderedContent()).append(',');
        field(builder, "receiver", ctx.receiver()).append(',');
        builder.append("\"occurredAt\":").append(ctx.event().occurredAt()).append(',');
        builder.append("\"attempt\":").append(ctx.attempt()).append(',');
        builder.append("\"tags\":").append(jsonMap(ctx.event().tags())).append(',');
        builder.append("\"params\":").append(jsonMap(ctx.event().params())).append('}');
        return builder.toString();
    }

    private static StringBuilder field(StringBuilder builder, String name, String value) {
        builder.append('"').append(name).append("\":").append(jsonValue(value));
        return builder;
    }

    private static String jsonMap(Map<?, ?> map) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        map.forEach((key, value) -> joiner.add(jsonValue(String.valueOf(key)) + ":" + jsonValue(String.valueOf(value))));
        return joiner.toString();
    }

    private static String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private static String sign(String content, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= 1024 ? body : body.substring(0, 1024);
    }
}
