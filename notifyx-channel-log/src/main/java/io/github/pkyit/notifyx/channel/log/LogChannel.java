package io.github.pkyit.notifyx.channel.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;

/**
 * 兜底日志渠道：把渲染后的内容以结构化日志输出。
 *
 * <p>它是所有 fallback-chain 的最终节点——即使所有外部渠道都挂了，通知内容也能从日志里捞回来。</p>
 *
 * <p>@implNote 线程安全（SLF4J Logger 线程安全）。</p>
 *
 * @since 1.0
 */
public class LogChannel implements NotifyChannel {

    /** 渠道名。 */
    public static final String NAME = "log";

    private final Logger log;

    /**
     * 构造渠道。
     *
     * @param loggerName 输出使用的 logger 名
     */
    public LogChannel(String loggerName) {
        this.log = LoggerFactory.getLogger(loggerName == null || loggerName.isBlank() ? "NOTIFYX_FALLBACK" : loggerName);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public SendResult send(NotifyContext ctx) {
        long started = System.nanoTime();
        log.info("scene={} level={} receiver={} title={} content={}",
                ctx.event().scene(), ctx.event().level().name(), ctx.receiver(),
                ctx.renderedTitle(), ctx.renderedContent());
        return SendResult.success(ctx.event().eventId(), null,
                java.time.Duration.ofNanos(System.nanoTime() - started));
    }
}
