package io.github.pkyit.notifyx.core.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;

/**
 * 默认审计实现：把审计记录以结构化格式写入专用 logger，便于日志系统采集。
 *
 * <p>@implNote 线程安全；实现内部吞掉所有异常，绝不影响发送主流程。</p>
 *
 * @since 1.0
 */
public final class LoggingAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger("NOTIFYX_AUDIT");

    @Override
    public void record(AuditRecord record) {
        try {
            log.info("eventId={} scene={} level={} channel={} receiver={} status={} attempts={} costMs={} traceId={}",
                    record.eventId(), record.scene(), record.level(), record.channel(), record.receiver(),
                    record.status(), record.attempts(), record.costMs(), record.traceId());
        } catch (RuntimeException ex) {
            log.debug("failed to write audit record: {}", ex.toString());
        }
    }
}
