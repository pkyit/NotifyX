package io.github.pkyit.notifyx.channel.email;

import java.time.Duration;
import java.util.Date;
import java.util.Properties;

import io.github.pkyit.notifyx.core.exception.ChannelAuthException;
import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.exception.ChannelProtocolException;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Jakarta Mail 邮件渠道。
 *
 * <p>错误分类：认证失败 → {@link ChannelAuthException}（不可重试）；协议错误 →
 * {@link ChannelProtocolException}（不可重试）；其余连接/超时 → {@link ChannelException}（可重试）。</p>
 *
 * <p>@implNote 线程安全：{@link Session} 与 {@link Transport#send} 均支持并发调用。</p>
 *
 * @since 1.0
 */
public class EmailChannel implements NotifyChannel {

    /** 渠道名。 */
    public static final String NAME = "email";

    private final EmailChannelProperties properties;
    private final Session session;

    /**
     * 构造渠道。
     *
     * @param properties 配置
     */
    public EmailChannel(EmailChannelProperties properties) {
        this.properties = properties;
        this.session = Session.getInstance(mailProperties(properties), null);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SendResult send(NotifyContext ctx) throws ChannelException {
        long started = System.nanoTime();
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(properties.getFrom(), properties.getFromName(), "UTF-8"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(ctx.receiver(), false));
            message.setSubject(ctx.renderedTitle() == null ? ctx.event().scene() : ctx.renderedTitle(), "UTF-8");
            message.setText(ctx.renderedContent(), "UTF-8");
            message.setSentDate(new Date());
            message.setHeader("Message-ID", "<" + ctx.event().eventId() + "@notifyx>");
            message.setHeader("X-Notifyx-Event-Id", ctx.event().eventId());
            Transport.send(message);
            return SendResult.success(message.getMessageID(), null, Duration.ofNanos(System.nanoTime() - started));
        } catch (AuthenticationFailedException ex) {
            throw new ChannelAuthException(NAME, "SMTP authentication failed", ex);
        } catch (MessagingException ex) {
            throw classify(ex);
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new ChannelProtocolException(NAME, "invalid sender address", ex);
        }
    }

    private ChannelException classify(MessagingException ex) {
        String text = String.valueOf(ex.getMessage()).toLowerCase();
        if (text.contains("535") || text.contains("authentication")) {
            return new ChannelAuthException(NAME, String.valueOf(ex.getMessage()), ex);
        }
        if (text.contains("550") || text.contains("552") || text.contains("invalid address")) {
            return new ChannelProtocolException(NAME, String.valueOf(ex.getMessage()), ex);
        }
        return new ChannelException(NAME, String.valueOf(ex.getMessage()), ex);
    }

    private static Properties mailProperties(EmailChannelProperties properties) {
        Properties javaMail = new Properties();
        javaMail.put("mail.smtp.host", properties.getHost());
        javaMail.put("mail.smtp.port", String.valueOf(properties.getPort()));
        javaMail.put("mail.smtp.connectiontimeout", String.valueOf(properties.getConnectTimeout().toMillis()));
        javaMail.put("mail.smtp.timeout", String.valueOf(properties.getWriteTimeout().toMillis()));
        javaMail.put("mail.smtp.writetimeout", String.valueOf(properties.getWriteTimeout().toMillis()));
        if (properties.isSsl()) {
            javaMail.put("mail.smtp.ssl.enable", "true");
        }
        if (properties.isStarttls()) {
            javaMail.put("mail.smtp.starttls.enable", "true");
        }
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            javaMail.put("mail.smtp.auth", "true");
        }
        return javaMail;
    }
}