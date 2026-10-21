package io.github.pkyit.notifyx.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 绑定器测试：验证「上下文就绪后回填门面、关闭时解绑」以及 AsyncAppender 包装场景。
 *
 * @since 1.0
 */
class NotifyxAppenderBinderTest {

    private LoggerContext context;
    private Logger logger;
    private NotifyxAppender appender;
    private AsyncAppender asyncAppender;

    @AfterEach
    void tearDown() {
        if (asyncAppender != null) {
            asyncAppender.stop();
        }
        if (appender != null) {
            appender.stop();
        }
        if (logger != null) {
            logger.detachAppender(appender);
            if (asyncAppender != null) {
                logger.detachAppender(asyncAppender);
            }
        }
    }

    @Test
    @DisplayName("绑定器把 NotifyKit 注入已挂载的 appender，并在 destroy 时解绑")
    void shouldBindAndUnbind() {
        prepare(false);
        RecordingNotifyKit kit = new RecordingNotifyKit();
        NotifyxAppenderBinder binder = new NotifyxAppenderBinder(kit);

        binder.afterSingletonsInstantiated();
        assertThat(appender.getNotifyKit()).isSameAs(kit);
        assertThat(binder.getBoundAppenders()).isGreaterThanOrEqualTo(1);

        binder.destroy();
        assertThat(appender.getNotifyKit()).isNull();
    }

    @Test
    @DisplayName("appender 包在 AsyncAppender 里也能被递归绑定")
    void shouldBindInsideAsyncAppender() {
        prepare(true);
        RecordingNotifyKit kit = new RecordingNotifyKit();
        NotifyxAppenderBinder binder = new NotifyxAppenderBinder(kit);

        binder.afterSingletonsInstantiated();
        assertThat(appender.getNotifyKit()).isSameAs(kit);
    }

    private void prepare(boolean wrapAsync) {
        // 绑定器读的是全局 LoggerFactory，因此测试挂到真实上下文的一个独立 logger 上
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("notifyx.binder.test." + System.nanoTime());
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);

        appender = new NotifyxAppender();
        appender.setContext(context);
        appender.setName("NOTIFYX-TEST");
        appender.setScene("LOG_ERROR");
        appender.start();

        if (wrapAsync) {
            asyncAppender = new AsyncAppender();
            asyncAppender.setContext(context);
            asyncAppender.setName("ASYNC-TEST");
            asyncAppender.start();
            asyncAppender.addAppender((Appender<ILoggingEvent>) appender);
            logger.addAppender(asyncAppender);
        } else {
            logger.addAppender(appender);
        }
    }
}
