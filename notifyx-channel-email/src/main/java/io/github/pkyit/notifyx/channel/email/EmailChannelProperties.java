package io.github.pkyit.notifyx.channel.email;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮件渠道配置（{@code notifyx.channels.email.*}）。
 *
 * <p>@implNote 可变 POJO，仅在启动期绑定。</p>
 *
 * @since 1.0
 */
@ConfigurationProperties(prefix = "notifyx.channels.email")
public class EmailChannelProperties {

    private boolean enabled;
    private String host;
    private int port = 25;
    private boolean ssl;
    private boolean starttls;
    private String username;
    private String password;
    private String from;
    private String fromName = "NotifyX";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration writeTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public boolean isStarttls() {
        return starttls;
    }

    public void setStarttls(boolean starttls) {
        this.starttls = starttls;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }
}