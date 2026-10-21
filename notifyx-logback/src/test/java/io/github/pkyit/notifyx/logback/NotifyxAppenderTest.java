package io.github.pkyit.notifyx.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * Appender 行为测试：映射、过滤、丢弃、排空与 T7 防自递归。
 *
 * @since 1.0
 */
class NotifyxAppenderTest {

    private static final Marker ALARM = MarkerFactory.getMarker("ALARM");

    private LoggerContext context;
    private Logger logger;
    private NotifyxAppender appender;
    private RecordingNotifyKit kit;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        context.setName("notifyx-appender-test");
        logger = context.getLogger("com.example.OrderService");
        logger.setLevel(Level.DEBUG);
        kit = new RecordingNotifyKit();

        appender = new NotifyxAppender();
        appender.setContext(context);
        appender.setScene("ORDER_CREATE_FAIL");
        appender.setQueueSize(64);
        appender.setStopAwaitMillis(200);
        NotifyxAppender.ExtractBizId extractBizId = new NotifyxAppender.ExtractBizId();
        extractBizId.setRegex("orderId=(\\w+)");
        extractBizId.setGroup(1);
        appender.setExtractBizId(extractBizId);
        appender.setNotifyKit(kit);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        context.stop();
    }

    @Test
    @DisplayName("ERROR 日志 → NotifyEvent，带 bizId / logger / 堆栈")
    void shouldMapErrorLogToEvent() throws Exception {
        logger.error(ALARM, "orderId=SO20261022001 创建失败", new IllegalStateException("库存不足"));

        NotifyEvent event = kit.awaitFirst(2000);
        assertThat(event.scene()).isEqualTo("ORDER_CREATE_FAIL");
        assertThat(event.level()).isEqualTo(io.github.pkyit.notifyx.core.model.Level.ERROR);
        assertThat(event.bizId()).isEqualTo("SO20261022001");
        assertThat(event.title()).startsWith("orderId=SO20261022001");
        assertThat(event.params()).containsKeys("logger", "thread", "message", "stackTrace");
        assertThat(event.params().get("logger")).isEqualTo("com.example.OrderService");
        assertThat(event.params().get("stackTrace").toString()).contains("IllegalStateException");
        assertThat(event.tag("source")).isEqualTo("logback");
        assertThat(event.tag("logLevel")).isEqualTo("ERROR");
        assertThat(event.occurredAt()).isPositive();
    }

    @Test
    @DisplayName("低于配置级别的日志不产生事件")
    void shouldIgnoreLogsBelowThreshold() throws Exception {
        logger.info("orderId=SO1 正常");

        Thread.sleep(200);
        assertThat(kit.events()).isEmpty();
    }

    @Test
    @DisplayName("配置 marker 后，只接收带该 marker 的日志")
    void shouldFilterByMarker() throws Exception {
        appender.stop();
        appender.setMarker("ALARM");
        appender.start();

        logger.error("orderId=SO1 无 marker 的错误");
        logger.error(ALARM, "orderId=SO2 有 marker 的错误");

        NotifyEvent event = kit.awaitFirst(2000);
        assertThat(event.bizId()).isEqualTo("SO2");
        assertThat(appender.getDroppedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("suppressRegex 命中的噪声日志被抑制")
    void shouldSuppressNoisyLogs() throws Exception {
        appender.stop();
        appender.setSuppressRegex("Connection reset|Broken pipe");
        appender.start();

        logger.error("Connection reset by peer");
        logger.error("orderId=SO3 真实故障");

        NotifyEvent event = kit.awaitFirst(2000);
        assertThat(event.bizId()).isEqualTo("SO3");
        assertThat(appender.getSuppressedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("队列满时丢弃并计数，日志线程不被阻塞")
    void shouldDropWhenQueueFull() throws Exception {
        appender.stop();
        appender.setQueueSize(2);
        appender.setStopAwaitMillis(50);
        kit.delayEachSend(2000);
        appender.start();

        long started = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            logger.error("orderId=SO{} flood", i);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(500);
        assertThat(appender.getDiscardedCount()).isGreaterThan(10);
    }

    @Test
    @DisplayName("T7：NotifyX 自身 logger 的 ERROR 不产生事件（第一道防线）")
    void shouldNotRecurseOnInternalLoggers() throws Exception {
        Logger pipelineLogger = context.getLogger("io.github.pkyit.notifyx.core.pipeline.NotifyPipeline");
        pipelineLogger.setLevel(Level.DEBUG);
        pipelineLogger.addAppender(appender);
        Logger auditLogger = context.getLogger("NOTIFYX_AUDIT");
        auditLogger.setLevel(Level.DEBUG);
        auditLogger.addAppender(appender);

        try {
            pipelineLogger.error("notifyx internal failure");
            auditLogger.error("audit line");
            Thread.sleep(300);

            assertThat(kit.events()).isEmpty();
            assertThat(appender.getDroppedCount()).isGreaterThanOrEqualTo(2);
        } finally {
            pipelineLogger.detachAppender(appender);
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("T7：投递线程内产生的日志被重入保护丢弃（第二道防线）")
    void shouldGuardAgainstReentrantDispatch() throws Exception {
        kit.onSend(event -> logger.error("orderId=SO9 投递过程中又记了一条 ERROR"));
        appender.setScene("ORDER_CREATE_FAIL");

        logger.error("orderId=SO8 第一现场");
        kit.awaitCount(1, 2000);
        Thread.sleep(300);

        // 只有最初一条；重入日志没有变成新事件
        assertThat(kit.events()).hasSize(1);
        assertThat(appender.getDroppedCount()).isGreaterThanOrEqualTo(1);
        assertThat(appender.getSubmittedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("停止时排空队列，已入队事件不静默丢失")
    void shouldDrainOnStop() throws Exception {
        kit.delayEachSend(20);
        for (int i = 0; i < 5; i++) {
            logger.error("orderId=SO{} 收尾", i);
        }
        appender.stop();

        assertThat(kit.events()).hasSize(5);
        assertThat(appender.getSubmittedCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("非法 scene 启动失败并把错误写进 logback status，而不是外抛")
    void shouldRejectIllegalScene() {
        NotifyxAppender broken = new NotifyxAppender();
        broken.setContext(context);
        broken.setScene("order_fail");
        broken.start();

        assertThat(broken.isStarted()).isFalse();
        assertThat(broken.getStatusManager().getCopyOfStatusList())
                .anyMatch(status -> status.getMessage().contains("illegal scene"));
    }

    @Test
    @DisplayName("堆栈按 maxStackTraceLines 截断")
    void shouldTruncateStackTrace() throws Exception {
        appender.stop();
        appender.setMaxStackTraceLines(8);
        appender.start();

        logger.error("orderId=SO7 深层故障", deepFailure(10));

        NotifyEvent event = kit.awaitFirst(2000);
        String stackTrace = String.valueOf(event.params().get("stackTrace"));
        assertThat(stackTrace).startsWith("java.lang.RuntimeException: level 10");
        assertThat(stackTrace).contains("more lines truncated");
        assertThat(stackTrace.lines().count()).isLessThan(20);
    }

    private static RuntimeException deepFailure(int depth) {
        if (depth <= 0) {
            return new IllegalStateException("root cause");
        }
        return new RuntimeException("level " + depth, deepFailure(depth - 1));
    }
}
