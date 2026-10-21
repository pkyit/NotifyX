package io.github.pkyit.notifyx.autoconfigure.channel;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.pkyit.notifyx.autoconfigure.AlertProperties;
import io.github.pkyit.notifyx.core.AlertChannel;
import io.github.pkyit.notifyx.core.AlertEvent;

/**
 * 通用 Webhook 通道：把告警序列化为 JSON POST 到指定地址。
 *
 * <p>刻意不内置任何厂商 SDK：钉钉、飞书、企业微信、自建告警中心，都可以通过同一个
 * HTTP 端点 + 自定义 header 接入，避免和具体厂商耦合。</p>
 */
public class WebhookAlertChannel implements AlertChannel {

    private final RestClient client;
    private final String url;
    private final Map<String, String> headers;
    private final ObjectMapper objectMapper;

    public WebhookAlertChannel(AlertProperties.Webhook config, ObjectMapper objectMapper) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "notifyx.channels.webhook.url must be configured when notifyx.channels.webhook.enabled=true");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) config.getTimeout().toMillis());

        this.client = RestClient.builder().requestFactory(factory).build();
        this.url = config.getUrl();
        this.headers = Map.copyOf(config.getHeaders());
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void send(AlertEvent event) throws JsonProcessingException {
        RestClient.RequestBodySpec request = client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        headers.forEach(request::header);
        request.body(toJson(event)).retrieve().toBodilessEntity();
    }

    private String toJson(AlertEvent event) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>(8);
        payload.put("level", event.level().name());
        payload.put("title", event.title());
        payload.put("content", event.content());
        payload.put("source", event.source());
        payload.put("timestamp", event.timestamp().toString());
        payload.put("fingerprint", event.fingerprint());
        payload.put("tags", event.tags());
        payload.put("attributes", event.attributes());
        return objectMapper.writeValueAsString(payload);
    }
}
