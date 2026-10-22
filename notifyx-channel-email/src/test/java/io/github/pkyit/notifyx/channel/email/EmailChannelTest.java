package io.github.pkyit.notifyx.channel.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.github.pkyit.notifyx.core.exception.ChannelAuthException;
import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.exception.ChannelProtocolException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * 邮件渠道契约测试（设计书 §18.3）：报文结构、Message-ID 幂等、错误四分类。
 *
 * <p>用静态 mock 拦截 {@link Transport#send(MimeMessage)}，不需要真实 SMTP，
 * 也不会产生任何外发流量。</p>
 *
 * @since 1.0
 */
class EmailChannelTest {

    private MockedStatic<Transport> transport;
    private final AtomicReference<MimeMessage> sent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        transport = mockStatic(Transport.class);
        transport.when(() -> Transport.send(any(MimeMessage.class))).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    @DisplayName("构造并发送 MimeMessage：发件人/收件人/主题/正文/Message-ID")
    void shouldBuildMessage() throws Exception {
        EmailChannel channel = new EmailChannel(properties());
        NotifyContext ctx = context("【订单异常】SO1", "订单 SO1 创建失败", "ops@example.com");

        SendResult result = channel.send(ctx);

        assertThat(result.status()).isEqualTo(SendResult.Status.SUCCESS);
        assertThat(result.messageId()).contains(ctx.event().eventId());
        assertThat(result.cost()).isGreaterThanOrEqualTo(Duration.ZERO);

        MimeMessage message = sent.get();
        assertThat(message).isNotNull();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@example.com");
        assertThat(from.getPersonal()).isEqualTo("NotifyX 告警");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("ops@example.com");
        assertThat(message.getSubject()).isEqualTo("【订单异常】SO1");
        assertThat(message.getContent()).isEqualTo("订单 SO1 创建失败");
        assertThat(message.getHeader("Message-ID")[0]).contains(ctx.event().eventId());
        assertThat(message.getHeader("X-Notifyx-Event-Id")[0]).isEqualTo(ctx.event().eventId());
    }

    @Test
    @DisplayName("标题为空时用场景码作为主题")
    void shouldFallbackSubjectToScene() throws Exception {
        EmailChannel channel = new EmailChannel(properties());

        channel.send(context(null, "content", "ops@example.com"));

        assertThat(sent.get().getSubject()).isEqualTo("ORDER_CREATE_FAIL");
    }

    @Test
    @DisplayName("认证失败 → ChannelAuthException（不可重试）")
    void shouldMapAuthenticationFailure() {
        transport.when(() -> Transport.send(any(MimeMessage.class)))
                .thenThrow(new AuthenticationFailedException("535 authentication failed"));

        assertThatThrownBy(() -> new EmailChannel(properties()).send(context("t", "c", "ops@example.com")))
                .isInstanceOf(ChannelAuthException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    @DisplayName("550 拒收 → ChannelProtocolException（不可重试）")
    void shouldMapRejectedRecipient() {
        transport.when(() -> Transport.send(any(MimeMessage.class)))
                .thenThrow(new MessagingException("550 mailbox unavailable"));

        assertThatThrownBy(() -> new EmailChannel(properties()).send(context("t", "c", "ops@example.com")))
                .isInstanceOf(ChannelProtocolException.class)
                .hasMessageContaining("550");
    }

    @Test
    @DisplayName("连接超时等其它异常 → ChannelException（可重试）")
    void shouldMapGenericFailure() {
        transport.when(() -> Transport.send(any(MimeMessage.class)))
                .thenThrow(new MessagingException("connect timed out"));

        assertThatThrownBy(() -> new EmailChannel(properties()).send(context("t", "c", "ops@example.com")))
                .isInstanceOf(ChannelException.class)
                .isNotInstanceOf(ChannelAuthException.class)
                .isNotInstanceOf(ChannelProtocolException.class);
    }

    @Test
    @DisplayName("message 里含 authentication 关键字也归类为认证失败")
    void shouldClassifyAuthByMessageText() {
        transport.when(() -> Transport.send(any(MimeMessage.class)))
                .thenThrow(new MessagingException("SMTP authentication required"));

        assertThatThrownBy(() -> new EmailChannel(properties()).send(context("t", "c", "ops@example.com")))
                .isInstanceOf(ChannelAuthException.class);
    }

    private EmailChannelProperties properties() {
        EmailChannelProperties properties = new EmailChannelProperties();
        properties.setHost("smtp.example.com");
        properties.setPort(465);
        properties.setSsl(true);
        properties.setFrom("noreply@example.com");
        properties.setFromName("NotifyX 告警");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setWriteTimeout(Duration.ofSeconds(1));
        return properties;
    }

    @Test
    @DisplayName("有用户名时 Session 携带 Authenticator，Transport.send 才能提交凭据")
    void shouldProvideCredentialsWhenUsernameConfigured() {
        EmailChannelProperties properties = properties();
        properties.setUsername("user@163.com");
        properties.setPassword("auth-code");

        EmailChannel channel = new EmailChannel(properties);
        jakarta.mail.PasswordAuthentication authentication = channel.session()
                .requestPasswordAuthentication(null, 465, "smtp", null, null);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getUserName()).isEqualTo("user@163.com");
        assertThat(authentication.getPassword()).isEqualTo("auth-code");
    }

    @Test
    @DisplayName("无用户名时不注入凭据（匿名中继场景）")
    void shouldNotProvideCredentialsWithoutUsername() {
        EmailChannel channel = new EmailChannel(properties());

        assertThat(channel.session().requestPasswordAuthentication(null, 25, "smtp", null, null)).isNull();
    }

    private NotifyContext context(String title, String content, String receiver) {
        NotifyEvent event = NotifyEvent.builder("ORDER_CREATE_FAIL").level(Level.ERROR).bizId("SO1").build();
        return NotifyContext.builder(event)
                .renderedTitle(title)
                .renderedContent(content)
                .receiver(receiver)
                .build();
    }
}
