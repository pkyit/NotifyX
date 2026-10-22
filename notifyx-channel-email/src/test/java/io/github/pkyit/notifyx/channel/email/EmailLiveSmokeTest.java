package io.github.pkyit.notifyx.channel.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * 可选的真实 SMTP 冒烟测试：仅当环境变量齐备时执行，默认跳过（CI 无凭据）。
 *
 * <p>用法（凭据只在命令行/env 中，不入库）：</p>
 * <pre>
 * $env:NOTIFYX_SMTP_HOST="smtp.163.com"
 * $env:NOTIFYX_SMTP_PORT="465"
 * $env:NOTIFYX_SMTP_SSL="true"
 * $env:NOTIFYX_SMTP_USER="you@example.com"
 * $env:NOTIFYX_SMTP_PASS="auth-code"
 * $env:NOTIFYX_SMTP_TO="a@example.com,b@example.com"
 * mvn -B -pl notifyx-channel-email test -Dtest=EmailLiveSmokeTest
 * </pre>
 *
 * <p>它会真实发送邮件、产生真实流量；不要在无人值守的 CI 中开启。</p>
 *
 * @since 1.0
 */
@EnabledIfEnvironmentVariable(named = "NOTIFYX_SMTP_USER", matches = ".+")
class EmailLiveSmokeTest {

    @Test
    @DisplayName("真实 SMTP：向 NOTIFYX_SMTP_TO 中的每个收件人发送一封测试邮件")
    void shouldSendRealEmail() {
        String password = System.getenv("NOTIFYX_SMTP_PASS");
        String receivers = System.getenv("NOTIFYX_SMTP_TO");
        assumeTrue(password != null && !password.isBlank(), "NOTIFYX_SMTP_PASS 未设置，跳过真实发送");
        assumeTrue(receivers != null && !receivers.isBlank(), "NOTIFYX_SMTP_TO 未设置，跳过真实发送");

        EmailChannelProperties properties = new EmailChannelProperties();
        properties.setHost(env("NOTIFYX_SMTP_HOST", "smtp.163.com"));
        properties.setPort(Integer.parseInt(env("NOTIFYX_SMTP_PORT", "465")));
        properties.setSsl(Boolean.parseBoolean(env("NOTIFYX_SMTP_SSL", "true")));
        properties.setUsername(System.getenv("NOTIFYX_SMTP_USER"));
        properties.setPassword(password);
        properties.setFrom(System.getenv("NOTIFYX_SMTP_USER"));
        properties.setFromName("NotifyX Smoke");
        properties.setConnectTimeout(Duration.ofSeconds(15));
        properties.setWriteTimeout(Duration.ofSeconds(20));

        EmailChannel channel = new EmailChannel(properties);
        NotifyEvent event = NotifyEvent.builder("EMAIL_LIVE_SMOKE")
                .level(Level.INFO)
                .bizId("SMOKE-" + System.currentTimeMillis())
                .param("time", LocalDateTime.now().toString())
                .build();

        for (String receiver : receivers.split(",")) {
            String target = receiver.trim();
            if (target.isEmpty()) {
                continue;
            }
            SendResult result = channel.send(NotifyContext.builder(event)
                    .receiver(target)
                    .renderedTitle("NotifyX 真实邮件冒烟")
                    .renderedContent("这是 NotifyX EmailLiveSmokeTest 发送的真实邮件。\n收件人：" + target)
                    .build());
            System.out.println("LIVE_EMAIL " + target + " status=" + result.status()
                    + " messageId=" + result.messageId() + " cost=" + result.cost());
            assertThat(result.status()).isEqualTo(SendResult.Status.SUCCESS);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
