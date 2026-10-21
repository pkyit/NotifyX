package io.github.pkyit.notifyx.logback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用设计书 8.1 的 XML 原文配置 logback，验证 Appender 的配置契约（属性名、嵌套元素、类型转换）。
 *
 * <p>这比单测 setter 更重要：用户抄的就是这段 XML，Joran 解析失败会在启动期才暴露。</p>
 *
 * @since 1.0
 */
class NotifyxAppenderXmlTest {

    private LoggerContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
    }

    @Test
    @DisplayName("设计书 8.1 的 XML（子元素写法）能被 Joran 完整解析")
    void shouldParseDocumentedXml() throws Exception {
        configureFromResource("/logback-appender-test.xml");

        NotifyxAppender appender = findAppender(context);
        assertThat(appender).as("appender should be created from XML").isNotNull();
        assertThat(appender.isStarted()).isTrue();
        assertThat(appender.getScene()).isEqualTo("ORDER_CREATE_FAIL");
        assertThat(appender.getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.getMarker()).isEqualTo("ALARM");
        assertThat(appender.getExtractBizId()).isNotNull();
        assertThat(appender.getExtractBizId().getRegex()).isEqualTo("orderId=(\\w+)");
        assertThat(appender.getExtractBizId().getGroup()).isEqualTo(1);
        assertThat(appender.getSuppressRegex()).isEqualTo("Connection reset|Broken pipe");
        assertThat(appender.getMaxStackTraceLines()).isEqualTo(30);
        assertThat(appender.getQueueSize()).isEqualTo(1024);
        assertThat(appender.getDiscardingThreshold()).isZero();
    }

    @Test
    @DisplayName("扁平写法 extractBizIdRegex / extractBizIdGroup 同样生效")
    void shouldParseFlatForm() throws Exception {
        configure("""
                <configuration>
                    <appender name="NOTIFYX" class="io.github.pkyit.notifyx.logback.NotifyxAppender">
                        <scene>LOG_ERROR</scene>
                        <extractBizIdRegex>payId=(\\d+)</extractBizIdRegex>
                        <extractBizIdGroup>2</extractBizIdGroup>
                    </appender>
                    <root level="INFO"><appender-ref ref="NOTIFYX"/></root>
                </configuration>
                """);

        NotifyxAppender appender = findAppender(context);
        assertThat(appender).isNotNull();
        assertThat(appender.getExtractBizIdRegex()).isEqualTo("payId=(\\d+)");
        assertThat(appender.getExtractBizIdGroup()).isEqualTo(2);
    }

    @Test
    @DisplayName("属性写法 <extractBizId regex=..> 被 logback 1.5 忽略时必须显式告警，而不是静默失效")
    void shouldWarnOnAttributeForm() throws Exception {
        configure("""
                <configuration>
                    <appender name="NOTIFYX" class="io.github.pkyit.notifyx.logback.NotifyxAppender">
                        <scene>LOG_ERROR</scene>
                        <extractBizId regex="orderId=(\\w+)" group="1"/>
                    </appender>
                    <root level="INFO"><appender-ref ref="NOTIFYX"/></root>
                </configuration>
                """);

        NotifyxAppender appender = findAppender(context);
        assertThat(appender).isNotNull();
        assertThat(appender.isStarted()).isTrue();
        assertThat(appender.getExtractBizIdRegex()).isNull();
        assertThat(statusMessages()).anyMatch(message -> message.contains("NOT supported by logback 1.5"));
    }

    @Test
    @DisplayName("非法正则不让应用启动失败，而是把错误写进 logback status")
    void shouldSurviveBrokenRegex() throws Exception {
        configure("""
                <configuration>
                    <appender name="NOTIFYX" class="io.github.pkyit.notifyx.logback.NotifyxAppender">
                        <scene>LOG_ERROR</scene>
                        <suppressRegex>[unclosed</suppressRegex>
                    </appender>
                    <root level="INFO"><appender-ref ref="NOTIFYX"/></root>
                </configuration>
                """);

        NotifyxAppender appender = findAppender(context);
        assertThat(appender).isNotNull();
        assertThat(appender.isStarted()).isFalse();
        assertThat(statusMessages()).anyMatch(message -> message.contains("invalid regex"));
    }

    private void configureFromResource(String resource) throws Exception {
        context = new LoggerContext();
        context.setName("notifyx-xml-test");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("test resource").isNotNull();
            configurator.doConfigure(in);
        }
    }

    private void configure(String xml) throws Exception {
        context = new LoggerContext();
        context.setName("notifyx-xml-test");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private java.util.List<String> statusMessages() {
        return context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() >= Status.WARN)
                .map(Status::getMessage)
                .toList();
    }

    private static NotifyxAppender findAppender(LoggerContext context) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> iterator = root.iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> candidate = iterator.next();
            if (candidate instanceof NotifyxAppender notifyxAppender) {
                return notifyxAppender;
            }
        }
        return null;
    }
}
