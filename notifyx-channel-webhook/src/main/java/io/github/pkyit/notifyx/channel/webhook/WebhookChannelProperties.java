package io.github.pkyit.notifyx.channel.webhook;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook 渠道配置（{@code notifyx.channels.webhook.*}）。
 *
 * <p>@implNote 可变 POJO，仅在启动期绑定。</p>
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.channels.webhook")
public class WebhookChannelProperties {

    private boolean enabled;
    private String url;
    private String secret;
    private boolean signEnabled = true;
    private Map<String, String> headers = new LinkedHashMap<>();
    private List<Integer> expectStatus = new ArrayList<>(List.of(200, 201, 204));
    private List<String> allowedHosts = new ArrayList<>();
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean isSignEnabled() {
        return signEnabled;
    }

    public void setSignEnabled(boolean signEnabled) {
        this.signEnabled = signEnabled;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public List<Integer> getExpectStatus() {
        return expectStatus;
    }

    public void setExpectStatus(List<Integer> expectStatus) {
        this.expectStatus = expectStatus;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
