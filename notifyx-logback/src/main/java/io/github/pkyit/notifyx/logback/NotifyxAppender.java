package io.github.pkyit.notifyx.logback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Marker;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * L0 接入：把日志变成 NotifyX 事件（设计书 8.1 / T7）。
 *
 * <p>四条实现铁律：</p>
 * <ol>
 *   <li><b>必须异步</b>：{@code append} 只做快照入队，投递在独立守护线程完成，绝不阻塞日志线程；</li>
 *   <li><b>队列满时丢弃并计数</b>：{@code offer} 失败即丢弃，绝不阻塞、绝不 CallerRuns；</li>
 *   <li><b>内部异常全部吞掉</b>：最多 {@code addError} 到 logback status，绝不外抛；</li>
 *   <li><b>防自递归</b>：过滤 NotifyX 自身 logger + 线程级重入保护，两道防线（见 {@link #isNotifyxInternal}）。</li>
 * </ol>
 *
 * <p>logback 配置示例（logback-spring.xml）：</p>
 * <pre>{@code
 * <appender name="NOTIFYX" class="io.github.pkyit.notifyx.logback.NotifyxAppender">
 *     <scene>UNCAUGHT_EXCEPTION</scene>
 *     <level>ERROR</level>
 *     <marker>ALARM</marker>
 *     <extractBizId regex="orderId=(\w+)" group="1"/>
 *     <suppressRegex>Connection reset|Broken pipe</suppressRegex>
 *     <maxStackTraceLines>30</maxStackTraceLines>
 *     <queueSize>1024</queueSize>
 *     <discardingThreshold>0</discardingThreshold>
 * </appender>
 * }</pre>
 *
 * <p>@implNote 线程安全。{@code notifyKit} 可运行期替换（Spring 绑定器在上下文就绪后注入）；
 * 未注入时事件被丢弃并计入 {@code unbound}，绝不缓存——日志在启动早期的堆积没有投递价值。</p>
 *
 * @since 1.0
 */
public class NotifyxAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** 默认场景码，见设计书 8.1 的示例。 */
    public static final String DEFAULT_SCENE = "UNCAUGHT_EXCEPTION";

    /** 场景码正则，与 {@code NotifyEvent} 保持一致。 */
    private static final Pattern SCENE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** NotifyX 自身 logger 前缀，命中即丢弃（第一道防递归）。 */
    private static final String SELF_LOGGER_PREFIX = "io.github.pkyit.notifyx";

    /** 审计与兜底渠道 logger 前缀，命中即丢弃（它们会把已处理的通知再写回日志）。 */
    private static final String AUDIT_LOGGER_PREFIX = "NOTIFYX_";

    /** 单条日志文本上限，防止超大 payload 撑爆事件参数的 64KB 预算。 */
    private static final int MAX_MESSAGE_CHARS = 4000;

    /** 标题上限。 */
    private static final int MAX_TITLE_CHARS = 200;

    /** 第二道防递归：投递线程内的重入标记。 */
    private static final ThreadLocal<Boolean> IN_DISPATCH = new ThreadLocal<>();

    private static final long WORKER_POLL_MILLIS = 200L;

    private static final long STOP_JOIN_MILLIS = 2000L;

    private String scene = DEFAULT_SCENE;
    private Level level = Level.ERROR;
    private String marker;
    private ExtractBizId extractBizId;
    private String suppressRegex;
    private int maxStackTraceLines = 30;
    private int queueSize = 1024;
    private int discardingThreshold;
    private int stopAwaitMillis = (int) STOP_JOIN_MILLIS;

    private volatile NotifyKit notifyKit;

    private BlockingQueue<LogRecord> queue;
    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean();
    private Pattern suppressPattern;
    private Pattern bizIdPattern;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong unbound = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        if (isStarted()) {
            return;
        }
        if (!SCENE_PATTERN.matcher(scene == null ? "" : scene).matches()) {
            addError("notifyx appender: illegal scene '" + scene + "', expect ^[A-Z][A-Z0-9_]{0,63}$");
            return;
        }
        if (queueSize <= 0) {
            addError("notifyx appender: queueSize must be positive but was " + queueSize);
            return;
        }
        try {
            this.suppressPattern = compile(suppressRegex);
            if (extractBizId != null && extractBizId.getRegex() != null && !extractBizId.getRegex().isBlank()) {
                this.bizIdPattern = Pattern.compile(extractBizId.getRegex());
            }
        } catch (RuntimeException ex) {
            addError("notifyx appender: invalid regex configuration", ex);
            return;
        }
        if (extractBizId != null && (extractBizId.getRegex() == null || extractBizId.getRegex().isBlank())) {
            // logback 1.5 的 model 解析器不把嵌套元素上的属性写入对象（只支持子元素），
            // 因此设计书 8.1 早期示例 <extractBizId regex=".." group=".."/> 会被静默忽略——这里显式告警。
            addWarn("notifyx appender: <extractBizId> has no <regex> child. Attribute form "
                    + "<extractBizId regex=\"...\" group=\"...\"/> is NOT supported by logback 1.5; use "
                    + "<extractBizId><regex>..</regex><group>1</group></extractBizId> "
                    + "or <extractBizIdRegex>..</extractBizIdRegex>");
        }
        if (extractBizId != null) {
            int group = extractBizId.getGroup();
            if (bizIdPattern != null && (group < 0 || group > bizIdPattern.matcher("").groupCount())) {
                addError("notifyx appender: extractBizId group " + group
                        + " exceeds pattern group count " + bizIdPattern.matcher("").groupCount());
                return;
            }
        }
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.running.set(true);
        super.start();
        this.worker = new Thread(this::drainLoop, "notifyx-logback-" + getName());
        this.worker.setDaemon(true);
        this.worker.start();
        addInfo("notifyx appender started: scene=" + scene + ", level=" + level + ", queueSize=" + queueSize);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        if (!isStarted()) {
            super.stop();
            return;
        }
        running.set(false);
        Thread current = this.worker;
        if (current != null) {
            try {
                current.join(Math.max(0, stopAwaitMillis));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (current.isAlive()) {
                current.interrupt();
                addWarn("notifyx appender: worker did not finish in " + stopAwaitMillis
                        + "ms, " + (queue == null ? 0 : queue.size()) + " events dropped");
            }
        }
        super.stop();
        addInfo("notifyx appender stopped: submitted=" + submitted.get() + ", discarded=" + discarded.get()
                + ", suppressed=" + suppressed.get() + ", unbound=" + unbound.get() + ", errors=" + errors.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || !running.get()) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(IN_DISPATCH.get())) {
                // 铁律 4 第二道防线：投递线程里产生的日志直接丢弃，避免「通知失败→记日志→再通知」的闭环
                dropped.incrementAndGet();
                return;
            }
            if (isNotifyxInternal(event.getLoggerName())) {
                dropped.incrementAndGet();
                return;
            }
            if (!event.getLevel().isGreaterOrEqual(level)) {
                return;
            }
            if (!markerMatches(event)) {
                dropped.incrementAndGet();
                return;
            }
            String formatted = safeFormattedMessage(event);
            if (suppressPattern != null && suppressPattern.matcher(formatted).find()) {
                suppressed.incrementAndGet();
                return;
            }
            if (discardingThreshold > 0 && queue.remainingCapacity() < discardingThreshold
                    && event.getLevel().toInt() < Level.WARN_INT) {
                dropped.incrementAndGet();
                return;
            }
            LogRecord record = snapshot(event, formatted);
            if (!queue.offer(record)) {
                discarded.incrementAndGet();
                return;
            }
        } catch (Throwable ex) {
            // 铁律 3：绝不外抛，否则 logback 会摘除 appender 并影响其他 appender
            errors.incrementAndGet();
            addError("notifyx appender: unexpected failure while accepting log event", ex);
        }
    }

    private LogRecord snapshot(ILoggingEvent event, String formatted) {
        List<Marker> markers = event.getMarkerList();
        String markerName = markers == null || markers.isEmpty() ? null : markers.get(0).getName();
        return new LogRecord(
                event.getLoggerName(),
                event.getThreadName(),
                event.getLevel().toInt(),
                event.getLevel().toString(),
                event.getMessage(),
                formatted,
                event.getThrowableProxy(),
                extractBizId(formatted),
                markerName,
                event.getTimeStamp());
    }

    private boolean markerMatches(ILoggingEvent event) {
        if (marker == null || marker.isBlank()) {
            return true;
        }
        List<Marker> markers = event.getMarkerList();
        if (markers == null) {
            return false;
        }
        for (Marker candidate : markers) {
            if (marker.equals(candidate.getName())) {
                return true;
            }
        }
        return false;
    }

    private String extractBizId(String formatted) {
        if (bizIdPattern == null || formatted == null) {
            return null;
        }
        Matcher matcher = bizIdPattern.matcher(formatted);
        if (!matcher.find()) {
            return null;
        }
        int group = extractBizId == null ? 1 : extractBizId.getGroup();
        try {
            String value = matcher.group(group);
            return value == null || value.isBlank() ? null : value;
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    private void drainLoop() {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                // 被 stop() 中断：放弃剩余事件，避免关闭流程被慢渠道拖住（丢弃数已在 stop 日志里体现）
                return;
            }
            if (!running.get() && queue.isEmpty()) {
                return;
            }
            LogRecord record;
            try {
                record = queue.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (record != null) {
                dispatch(record);
            }
        }
    }

    private void dispatch(LogRecord record) {
        NotifyKit kit = this.notifyKit;
        if (kit == null) {
            unbound.incrementAndGet();
            return;
        }
        // 铁律 4 的第二道防线：即使 logger 名过滤被绕过，投递线程内的重入也直接丢弃
        if (Boolean.TRUE.equals(IN_DISPATCH.get())) {
            dropped.incrementAndGet();
            return;
        }
        IN_DISPATCH.set(Boolean.TRUE);
        try {
            kit.send(toEvent(record));
            submitted.incrementAndGet();
        } catch (Throwable ex) {
            errors.incrementAndGet();
            addError("notifyx appender: dispatch failed for logger=" + record.loggerName(), ex);
        } finally {
            IN_DISPATCH.remove();
        }
    }

    private NotifyEvent toEvent(LogRecord record) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "logger", record.loggerName());
        putIfPresent(params, "thread", record.threadName());
        putIfPresent(params, "message", record.message());
        putIfPresent(params, "formattedMessage", record.formattedMessage());
        putIfPresent(params, "marker", record.marker());
        putIfPresent(params, "bizId", record.bizId());
        String stackTrace = truncateStackTrace(record.throwable());
        putIfPresent(params, "stackTrace", stackTrace);

        NotifyEvent.Builder builder = NotifyEvent.builder(scene)
                .level(mapLevel(record.level()))
                .title(titleOf(record))
                .occurredAt(record.timestamp())
                .params(params)
                .tag("source", "logback")
                .tag("logLevel", record.levelName())
                .tag("logger", record.loggerName() == null ? "" : record.loggerName());
        if (record.bizId() != null) {
            builder.bizId(record.bizId());
        }
        return builder.build();
    }

    private static io.github.pkyit.notifyx.core.model.Level mapLevel(int logbackLevel) {
        if (logbackLevel >= Level.ERROR_INT) {
            return io.github.pkyit.notifyx.core.model.Level.ERROR;
        }
        if (logbackLevel >= Level.WARN_INT) {
            return io.github.pkyit.notifyx.core.model.Level.WARN;
        }
        return io.github.pkyit.notifyx.core.model.Level.INFO;
    }

    private static String titleOf(LogRecord record) {
        String formatted = record.formattedMessage();
        if (formatted == null || formatted.isBlank()) {
            return "[" + record.levelName() + "] " + (record.loggerName() == null ? "unknown" : record.loggerName());
        }
        int lineEnd = formatted.indexOf('\n');
        String firstLine = (lineEnd < 0 ? formatted : formatted.substring(0, lineEnd)).trim();
        return firstLine.length() <= MAX_TITLE_CHARS ? firstLine : firstLine.substring(0, MAX_TITLE_CHARS) + "…";
    }

    private String truncateStackTrace(IThrowableProxy throwable) {
        if (throwable == null || maxStackTraceLines <= 0) {
            return null;
        }
        String text = ThrowableProxyUtil.asString(throwable);
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxStackTraceLines) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < maxStackTraceLines; i++) {
            builder.append(lines[i]).append('\n');
        }
        builder.append("... (").append(lines.length - maxStackTraceLines).append(" more lines truncated)");
        return builder.toString();
    }

    private static String safeFormattedMessage(ILoggingEvent event) {
        try {
            String formatted = event.getFormattedMessage();
            if (formatted == null) {
                return "";
            }
            return formatted.length() <= MAX_MESSAGE_CHARS
                    ? formatted : formatted.substring(0, MAX_MESSAGE_CHARS) + "…";
        } catch (RuntimeException ex) {
            return String.valueOf(event.getMessage());
        }
    }

    private static boolean isNotifyxInternal(String loggerName) {
        return loggerName != null
                && (loggerName.startsWith(SELF_LOGGER_PREFIX) || loggerName.startsWith(AUDIT_LOGGER_PREFIX));
    }

    private static void putIfPresent(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isEmpty()) {
            params.put(key, value);
        }
    }

    private static Pattern compile(String regex) {
        return regex == null || regex.isBlank() ? null : Pattern.compile(regex);
    }

    /**
     * 注入门面。Spring 环境由 {@link NotifyxAppenderBinder} 在上下文就绪后调用；
     * 非 Spring 环境可手动 {@code appender.setNotifyKit(kit)}。
     *
     * @param notifyKit 门面，可为 null（表示解绑）
     */
    public void setNotifyKit(NotifyKit notifyKit) {
        this.notifyKit = notifyKit;
    }

    /**
     * 当前绑定的门面。
     *
     * @return 门面或 null
     */
    public NotifyKit getNotifyKit() {
        return notifyKit;
    }

    public String getScene() {
        return scene;
    }

    /**
     * 设置场景码，必须是 {@code ^[A-Z][A-Z0-9_]{0,63}$}。
     *
     * @param scene 场景码
     */
    public void setScene(String scene) {
        this.scene = scene;
    }

    public Level getLevel() {
        return level;
    }

    /**
     * 最低接收级别，低于该级别的日志直接忽略。
     *
     * @param level logback 级别
     */
    public void setLevel(Level level) {
        this.level = level == null ? Level.ERROR : level;
    }

    public String getMarker() {
        return marker;
    }

    /**
     * 只接收带该 marker 的日志；null 表示不按 marker 过滤。
     *
     * @param marker marker 名
     */
    public void setMarker(String marker) {
        this.marker = marker;
    }

    public ExtractBizId getExtractBizId() {
        return extractBizId;
    }

    /**
     * 业务主键提取规则（对应 XML 的 {@code <extractBizId regex="..." group="1"/>}）。
     *
     * @param extractBizId 提取规则
     */
    public void setExtractBizId(ExtractBizId extractBizId) {
        this.extractBizId = extractBizId;
    }

    /**
     * 业务主键提取正则（扁平写法，等价于 {@code <extractBizId><regex>..</regex></extractBizId>}）。
     *
     * @return 正则或 null
     */
    public String getExtractBizIdRegex() {
        return extractBizId == null ? null : extractBizId.getRegex();
    }

    /**
     * 设置业务主键提取正则。
     *
     * @param regex 正则
     */
    public void setExtractBizIdRegex(String regex) {
        ensureExtractBizId().setRegex(regex);
    }

    /**
     * 业务主键捕获组。
     *
     * @return 组号
     */
    public int getExtractBizIdGroup() {
        return extractBizId == null ? 1 : extractBizId.getGroup();
    }

    /**
     * 设置业务主键捕获组。
     *
     * @param group 组号
     */
    public void setExtractBizIdGroup(int group) {
        ensureExtractBizId().setGroup(group);
    }

    private ExtractBizId ensureExtractBizId() {
        if (extractBizId == null) {
            extractBizId = new ExtractBizId();
        }
        return extractBizId;
    }

    public String getSuppressRegex() {
        return suppressRegex;
    }

    /**
     * 噪声过滤正则，命中即丢弃（见设计书 8.1 关于 L0 可用性的说明）。
     *
     * @param suppressRegex 正则
     */
    public void setSuppressRegex(String suppressRegex) {
        this.suppressRegex = suppressRegex;
    }

    public int getMaxStackTraceLines() {
        return maxStackTraceLines;
    }

    /**
     * 堆栈截断行数，0 表示不附带堆栈。
     *
     * @param maxStackTraceLines 行数
     */
    public void setMaxStackTraceLines(int maxStackTraceLines) {
        this.maxStackTraceLines = maxStackTraceLines;
    }

    public int getQueueSize() {
        return queueSize;
    }

    /**
     * 有界队列容量。
     *
     * @param queueSize 容量
     */
    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public int getDiscardingThreshold() {
        return discardingThreshold;
    }

    /**
     * 剩余容量低于该值时丢弃 WARN 以下事件；0 表示不按水位丢弃（只受队列容量限制）。
     *
     * @param discardingThreshold 阈值
     */
    public void setDiscardingThreshold(int discardingThreshold) {
        this.discardingThreshold = discardingThreshold;
    }

    public int getStopAwaitMillis() {
        return stopAwaitMillis;
    }

    /**
     * 停止时等待队列排空的时长。
     *
     * @param stopAwaitMillis 毫秒
     */
    public void setStopAwaitMillis(int stopAwaitMillis) {
        this.stopAwaitMillis = stopAwaitMillis;
    }

    public long getSubmittedCount() {
        return submitted.get();
    }

    public long getDroppedCount() {
        return dropped.get();
    }

    public long getDiscardedCount() {
        return discarded.get();
    }

    public long getSuppressedCount() {
        return suppressed.get();
    }

    public long getUnboundCount() {
        return unbound.get();
    }

    public long getErrorCount() {
        return errors.get();
    }

    /**
     * 当前队列水位。
     *
     * @return 队列长度，未启动时为 0
     */
    public int getQueueDepth() {
        BlockingQueue<LogRecord> current = this.queue;
        return current == null ? 0 : current.size();
    }

    /**
     * 业务主键提取规则：从格式化后的日志文本里取一段作为 {@code bizId}。
     *
     * <p>@implNote 可变 POJO，仅由 logback 配置器在启动前设置；运行期只读。</p>
     *
     * @since 1.0
     */
    public static final class ExtractBizId {

        private String regex;
        private int group = 1;

        public String getRegex() {
            return regex;
        }

        public void setRegex(String regex) {
            this.regex = regex;
        }

        public int getGroup() {
            return group;
        }

        public void setGroup(int group) {
            this.group = group;
        }
    }

    /**
     * 日志快照：只保留投递需要的不可变字段，避免持有 logback 事件对象。
     *
     * @implNote 不可变，线程安全。
     */
    private record LogRecord(String loggerName, String threadName, int level, String levelName,
                             String message, String formattedMessage, IThrowableProxy throwable,
                             String bizId, String marker, long timestamp) {
    }
}
