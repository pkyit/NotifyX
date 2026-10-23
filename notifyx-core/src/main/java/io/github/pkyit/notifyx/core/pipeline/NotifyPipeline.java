package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.exception.ChannelException;
import io.github.pkyit.notifyx.core.metrics.NotifyMetrics;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.model.RenderFormat;
import io.github.pkyit.notifyx.core.model.SendResult;
import io.github.pkyit.notifyx.core.spi.AuditRecord;
import io.github.pkyit.notifyx.core.spi.AuditRecorder;
import io.github.pkyit.notifyx.core.spi.DedupeStore;
import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;
import io.github.pkyit.notifyx.core.spi.Masker;
import io.github.pkyit.notifyx.core.spi.NotifyChannel;
import io.github.pkyit.notifyx.core.spi.NotifyContext;
import io.github.pkyit.notifyx.core.spi.RecordStatus;
import io.github.pkyit.notifyx.core.spi.TemplateEngine;
import io.github.pkyit.notifyx.core.spi.TraceContext;
import io.github.pkyit.notifyx.core.support.Fingerprints;
import io.github.pkyit.notifyx.core.support.LoggingAuditRecorder;
import io.github.pkyit.notifyx.core.support.NotifyEnvelopeCodec;

/**
 * 责任链管线：从事件到投递的唯一通道。
 *
 * <p>固定顺序：过滤链（全局静默 → 场景路由 → 免打扰 → 去重 → 场景限流 → 全局限流）
 * → 渲染 → fan-out → 渠道发送（含重试与熔断）→ 审计 → 失败落盘。</p>
 *
 * <p>任何过滤器或渠道抛出的异常都被吞掉并记录，绝不允许外抛到调用线程（承诺 P2）。</p>
 *
 * <p>@implNote 线程安全：所有可变状态均为并发容器或不可变快照。</p>
 *
 * @since 1.0
 */
public final class NotifyPipeline {

    private static final Logger log = LoggerFactory.getLogger(NotifyPipeline.class);

    private final SceneRegistry sceneRegistry;
    private final List<EventFilter> filters;
    private final ChannelRegistry channelRegistry;
    private final TemplateEngine templateEngine;
    private final Map<String, MessageTemplate> templates;
    private final DedupeStoreHolder dedupe;
    private final FailureStore failureStore;
    private final AuditRecorder auditRecorder;
    private final Masker masker;
    private final NotifyMetrics metrics;
    private final RetryPolicy retryPolicy;
    private final String instanceId;
    private final boolean maskBeforeAudit;
    private final DeferredHandler deferredHandler;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitConfig circuitConfig;
    private final TraceContext traceContext;
    private final EventAggregator aggregator;
    private final EscalationPolicy escalation;

    /**
     * {@link DedupeStore} 持有者，便于失败时释放。
     *
     * @param store 存储
     * @since 1.0
     */
    public record DedupeStoreHolder(DedupeStore store) {
    }

    private NotifyPipeline(Builder builder) {
        this.sceneRegistry = Objects.requireNonNull(builder.sceneRegistry, "sceneRegistry");
        this.filters = List.copyOf(builder.filters);
        this.channelRegistry = Objects.requireNonNull(builder.channelRegistry, "channelRegistry");
        this.templateEngine = builder.templateEngine;
        this.templates = Map.copyOf(builder.templates);
        this.dedupe = builder.dedupe;
        this.failureStore = builder.failureStore;
        this.auditRecorder = builder.auditRecorder;
        this.masker = builder.masker;
        this.metrics = builder.metrics;
        this.retryPolicy = builder.retryPolicy;
        this.instanceId = builder.instanceId;
        this.maskBeforeAudit = builder.maskBeforeAudit;
        this.deferredHandler = builder.deferredHandler;
        this.circuitConfig = builder.circuitConfig;
        this.traceContext = builder.traceContext == null ? TraceContext.none() : builder.traceContext;
        this.aggregator = builder.aggregator;
        this.escalation = builder.escalation;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 处理一个事件（同步执行，通常运行在通知线程池中）。
     *
     * <p>返回值用于失败补偿判断：REJECT/异常返回 false，DEFER 返回 true（已受理），
     * 投递完成且没有任何渠道失败也返回 true。</p>
     *
     * @param event 事件
     * @return 是否被成功受理/投递
     */
    public boolean handle(NotifyEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            metrics.eventReceived(event, "L4");
            SceneConfig scene = sceneRegistry.find(event.scene());
            Level level = event.level().atLeast(scene.level()) ? event.level() : scene.level();
            scene = applyEscalation(event, scene);
            Duration silentWindow = resolveSilentWindow(event, scene);
            Map<String, Object> renderParams = renderParams(event, level);
            String dedupeKey = resolveDedupeKey(event, renderParams);

            FilterContext filterContext = new FilterContext(event, scene, Instant.now(), dedupeKey, silentWindow);
            FilterVerdict verdict = runFilters(event, filterContext);
            if (verdict.decision() == FilterVerdict.Decision.REJECT) {
                if (aggregator != null && "duplicate".equals(verdict.reason())) {
                    // 去重命中的事件不丢弃：进入实例内聚合，窗口结束发一条摘要（§9.5 T0）
                    aggregator.record(event, level, dedupeKey, instanceId);
                }
                reject(event, verdict.reason());
                return false;
            }
            if (verdict.decision() == FilterVerdict.Decision.DEFER) {
                defer(event, verdict);
                return true;
            }
            boolean failed = dispatch(event, scene, level, renderParams, dedupeKey);
            if (!failed && escalation != null) {
                escalation.recordSuccess(event.scene());
            }
            return !failed;
        } catch (RuntimeException ex) {
            log.warn("notifyx pipeline failed for scene={} eventId={}: {}", event.scene(), event.eventId(), ex.toString());
            return false;
        }
    }

    /**
     * 应用升级策略：记录一次事件，必要时把通道替换为 {@code upgrade-to}。
     *
     * @param event 事件
     * @param scene 原场景配置
     * @return 生效的场景配置
     */
    private SceneConfig applyEscalation(NotifyEvent event, SceneConfig scene) {
        if (escalation == null) {
            return scene;
        }
        if (escalation.isRecoveryScene(event.scene())) {
            List<String> recovered = escalation.recover(event.scene());
            log.info("notifyx escalation reset by recovery scene={} targets={}", event.scene(), recovered);
            return scene;
        }
        EscalationPolicy.Outcome outcome = escalation.record(event.scene(), System.currentTimeMillis());
        if (!outcome.escalated()) {
            return scene;
        }
        log.debug("notifyx escalation active scene={} channels={}", event.scene(), outcome.channels());
        return scene.withRouting(scene.level(), outcome.channels());
    }

    private FilterVerdict runFilters(NotifyEvent event, FilterContext context) {
        for (EventFilter filter : filters) {
            if (!filter.supports(event)) {
                continue;
            }
            try {
                FilterVerdict verdict = filter.doFilter(context);
                if (verdict == null || verdict.isPass()) {
                    continue;
                }
                metrics.filterRejected(filter.name(), event.scene());
                return verdict;
            } catch (RuntimeException ex) {
                log.warn("filter [{}] failed, continue: {}", filter.name(), ex.toString());
            }
        }
        return FilterVerdict.pass();
    }

    private boolean dispatch(NotifyEvent event, SceneConfig scene, Level level,
                             Map<String, Object> renderParams, String dedupeKey) {
        List<String> receivers = resolveReceivers(event, scene);
        List<NotifyChannel> channels = resolveChannels(scene);
        if (channels.isEmpty()) {
            log.debug("no channel available for scene={}", event.scene());
            return true;
        }
        boolean failed = false;
        for (NotifyChannel channel : channels) {
            if (channel.supportsBatch() && receivers.size() > 1) {
                failed |= sendOne(channel, event, level, renderParams, null, dedupeKey, scene);
            } else if (receivers.isEmpty()) {
                failed |= sendOne(channel, event, level, renderParams, null, dedupeKey, scene);
            } else {
                for (String receiver : receivers) {
                    failed |= sendOne(channel, event, level, renderParams, receiver, dedupeKey, scene);
                }
            }
        }
        if (failed && dedupe != null && dedupe.store() != null) {
            dedupe.store().release(dedupeKey);
        }
        return failed;
    }

    private boolean sendOne(NotifyChannel channel, NotifyEvent event, Level level, Map<String, Object> renderParams,
                            String receiver, String dedupeKey, SceneConfig scene) {
        String traceId = resolveTraceId(event);
        String spanId = resolveSpanId(event);
        Map<String, Object> tracedParams = withTrace(renderParams, traceId, spanId);
        int maxAttempts = retryPolicy.attemptsFor(channel);
        if (scene.retry() > 0) {
            maxAttempts = Math.min(maxAttempts, scene.retry());
        }
        String renderedTitle;
        String renderedContent;
        try {
            renderedTitle = templateEngine.render(templateFor(scene).title(), tracedParams,
                    channel.preferredFormat());
            renderedContent = templateEngine.render(templateFor(scene).content(), tracedParams,
                    channel.preferredFormat());
        } catch (RuntimeException ex) {
            String reason = "template render failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
            return failBeforeSend(event, level, channel, receiver,
                    SendResult.nonRetryable(reason, Duration.ZERO), dedupeKey);
        }
        NotifyContext baseContext = NotifyContext.builder(event)
                .renderedTitle(renderedTitle)
                .renderedContent(renderedContent)
                .renderFormat(channel.preferredFormat())
                .receiver(receiver)
                .traceId(traceId)
                .spanId(spanId)
                .instanceId(instanceId)
                .idempotencyKey(dedupeKey)
                .build();
        if (!channel.isEnabled(baseContext)) {
            return false;
        }

        CircuitBreaker breaker = breakerFor(channel.name());
        long started = System.nanoTime();
        SendResult result = null;
        int attemptsUsed = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!breaker.allowRequest()) {
                result = SendResult.suppressed(SendResult.Status.CIRCUIT_OPEN, "channel circuit open");
                break;
            }
            attemptsUsed = attempt;
            NotifyContext context = baseContext.withAttempt(attempt);
            try {
                result = channel.send(context);
                if (result == null) {
                    breaker.recordFailure();
                    if (attempt >= maxAttempts) {
                        result = SendResult.retryable("channel returned null", elapsed(started));
                        break;
                    }
                    if (!sleep(retryPolicy.delayAfter(attempt, channel))) {
                        result = SendResult.retryable("interrupted while retrying", elapsed(started));
                        break;
                    }
                    continue;
                }
                if (result.status() == SendResult.Status.SUCCESS) {
                    breaker.recordSuccess();
                    break;
                }
                if (result.status() == SendResult.Status.FAIL_RETRYABLE) {
                    breaker.recordFailure();
                    if (attempt >= maxAttempts) {
                        break;
                    }
                    if (!sleep(retryPolicy.delayAfter(attempt, channel))) {
                        result = SendResult.retryable("interrupted while retrying", elapsed(started));
                        break;
                    }
                    continue;
                }
                if (result.status() == SendResult.Status.FAIL_NON_RETRYABLE) {
                    breaker.recordFailure();
                    break;
                }
                // 渠道主动抑制（SUPPRESSED/DEDUPED/RATE_LIMITED/CIRCUIT_OPEN）不计熔断成功或失败
                break;
            } catch (ChannelException ex) {
                breaker.recordFailure();
                boolean retryable = isRetryable(channel, ex);
                if (!retryable || attempt >= maxAttempts) {
                    result = retryable
                            ? SendResult.retryable(ex.getMessage(), elapsed(started))
                            : SendResult.nonRetryable(ex.getMessage(), elapsed(started));
                    break;
                }
                if (!sleep(retryPolicy.delayAfter(attempt, channel))) {
                    result = SendResult.retryable("interrupted while retrying", elapsed(started));
                    break;
                }
            } catch (RuntimeException ex) {
                breaker.recordFailure();
                String reason = "unexpected channel error: " + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
                if (attempt >= maxAttempts) {
                    result = SendResult.retryable(reason, elapsed(started));
                    break;
                }
                if (!sleep(retryPolicy.delayAfter(attempt, channel))) {
                    result = SendResult.retryable("interrupted while retrying", elapsed(started));
                    break;
                }
            }
        }
        if (result == null) {
            // 防御性兜底：任何情况下都不能把「渠道没有结果」当成成功
            result = SendResult.retryable("channel produced no result", elapsed(started));
        }
        Duration cost = result.cost();
        audit(event, level, channel.name(), receiver, result, cost, attemptsUsed);
        metrics.sendResult(channel.name(), event.scene(), result.status(), cost);
        boolean failed = result.status() == SendResult.Status.FAIL_RETRYABLE
                || result.status() == SendResult.Status.FAIL_NON_RETRYABLE
                || result.status() == SendResult.Status.CIRCUIT_OPEN;
        if (failed) {
            persistFailure(event, channel.name(), result, attemptsUsed, dedupeKey);
            notifyOnError(event, result);
        } else if (result.status() == SendResult.Status.SUCCESS) {
            notifyOnSuccess(event);
        }
        return failed;
    }

    private boolean failBeforeSend(NotifyEvent event, Level level, NotifyChannel channel, String receiver,
                                   SendResult result, String dedupeKey) {
        // 渲染在渠道调用之前失败，因此真实渠道尝试次数为 0。
        audit(event, level, channel.name(), receiver, result, Duration.ZERO, 0);
        metrics.sendResult(channel.name(), event.scene(), result.status(), Duration.ZERO);
        persistFailure(event, channel.name(), result, 0, dedupeKey);
        notifyOnError(event, result);
        return true;
    }

    private void notifyOnSuccess(NotifyEvent event) {
        if (event.onSuccess() != null) {
            try {
                event.onSuccess().run();
            } catch (RuntimeException ex) {
                log.debug("onSuccess callback failed: {}", ex.toString());
            }
        }
    }

    private void notifyOnError(NotifyEvent event, SendResult result) {
        if (event.onError() != null) {
            try {
                event.onError().accept(new ChannelException("pipeline", String.valueOf(result.failureReason())));
            } catch (RuntimeException ex) {
                log.debug("onError callback failed: {}", ex.toString());
            }
        }
    }

    private void persistFailure(NotifyEvent event, String channel, SendResult result, int attempts, String dedupeKey) {
        if (failureStore == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            String envelopeJson;
            try {
                envelopeJson = NotifyEnvelopeCodec.toJson(NotifyEnvelope.of(event, dedupeKey,
                        resolveTraceId(event), resolveSpanId(event), instanceId, attempts, now));
            } catch (RuntimeException codecFailure) {
                // 报文序列化失败不能让落盘整体失败：退化为 eventId，至少留下线索
                log.debug("envelope serialization failed: {}", codecFailure.toString());
                envelopeJson = event.eventId();
            }
            FailedRecord record = new FailedRecord(event.eventId(), RecordStatus.PENDING, 0, envelopeJson,
                    channel, String.valueOf(result.failureReason()), attempts, now, now + 60_000L);
            failureStore.save(record);
            log.warn("event persisted for compensation eventId={} channel={}", event.eventId(), channel);
        } catch (RuntimeException ex) {
            log.warn("failed to persist failure record: {}", ex.toString());
        }
    }

    private void audit(NotifyEvent event, Level level, String channel, String receiver, SendResult result,
                       Duration cost, int attempts) {
        if (auditRecorder == null) {
            return;
        }
        String maskedReceiver = receiver;
        if (maskBeforeAudit && receiver != null && masker != null) {
            maskedReceiver = masker.maskText(receiver);
        }
        try {
            auditRecorder.record(new AuditRecord(event.eventId(), event.scene(), level.name(),
                    event.bizId(), channel, maskedReceiver,
                    result == null ? "UNKNOWN" : result.status().name(),
                    result == null ? null : result.failureReason(),
                    attempts, cost.toMillis(), instanceId,
                    resolveTraceId(event), System.currentTimeMillis(),
                    result != null && result.status() == SendResult.Status.SUCCESS
                            ? Long.valueOf(System.currentTimeMillis()) : null));
        } catch (RuntimeException ex) {
            log.debug("audit failed: {}", ex.toString());
        }
    }

    private void reject(NotifyEvent event, String reason) {
        log.debug("event rejected: scene={} reason={}", event.scene(), reason);
        audit(event, event.level(), "-", null,
                SendResult.suppressed(statusFor(reason), reason), Duration.ZERO, 0);
    }

    private void defer(NotifyEvent event, FilterVerdict verdict) {
        if (deferredHandler == null || verdict.deferUntil() == null) {
            reject(event, "deferred-unsupported");
            return;
        }
        deferredHandler.defer(event, verdict.deferUntil());
        audit(event, event.level(), "-", null,
                SendResult.suppressed(SendResult.Status.SUPPRESSED, "deferred"), Duration.ZERO, 0);
    }

    private static SendResult.Status statusFor(String reason) {
        if ("duplicate".equals(reason)) {
            return SendResult.Status.DEDUPED;
        }
        if (reason != null && reason.startsWith("rate-limited")) {
            return SendResult.Status.RATE_LIMITED;
        }
        return SendResult.Status.SUPPRESSED;
    }

    private boolean isRetryable(NotifyChannel channel, ChannelException ex) {
        for (Class<? extends Throwable> type : channel.nonRetryable()) {
            if (type.isInstance(ex)) {
                return false;
            }
        }
        return !(ex instanceof io.github.pkyit.notifyx.core.exception.ChannelAuthException)
                && !(ex instanceof io.github.pkyit.notifyx.core.exception.ChannelProtocolException);
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private CircuitBreaker breakerFor(String channel) {
        return breakers.computeIfAbsent(channel, name -> new CircuitBreaker(
                circuitConfig.failureThreshold(), circuitConfig.openDuration(), circuitConfig.maxOpenDuration()));
    }

    private MessageTemplate templateFor(SceneConfig scene) {
        MessageTemplate template = scene.template() == null ? null : templates.get(scene.template());
        if (template != null) {
            return template;
        }
        template = templates.get("default");
        return template == null
                ? new MessageTemplate("default", "[${level}] ${scene}", "${content}")
                : template;
    }

    private List<NotifyChannel> resolveChannels(SceneConfig scene) {
        List<NotifyChannel> resolved = new ArrayList<>();
        for (String name : scene.channels()) {
            Optional<NotifyChannel> channel = channelRegistry.find(name);
            if (channel.isPresent()) {
                resolved.add(channel.get());
            } else {
                log.warn("channel [{}] configured for scene {} not found", name, scene.scene());
            }
        }
        return resolved;
    }

    private List<String> resolveReceivers(NotifyEvent event, SceneConfig scene) {
        if (!event.receivers().isEmpty()) {
            return new ArrayList<>(event.receivers());
        }
        if (scene.receiversFrom() != null) {
            Object value = event.params().get(scene.receiversFrom().replace("params.", ""));
            if (value != null) {
                List<String> fromParams = new ArrayList<>();
                fromParams.add(String.valueOf(value));
                return fromParams;
            }
        }
        return new ArrayList<>(scene.receivers());
    }

    private Duration resolveSilentWindow(NotifyEvent event, SceneConfig scene) {
        if (event.silentWindow() != null) {
            return event.silentWindow();
        }
        if (scene.silentWindow() != null) {
            return scene.silentWindow();
        }
        return Duration.ofMinutes(5);
    }

    private String resolveDedupeKey(NotifyEvent event, Map<String, Object> renderParams) {
        if (event.dedupeKey() != null && !event.dedupeKey().isBlank()) {
            return event.dedupeKey();
        }
        if (event.bizId() != null && !event.bizId().isBlank()) {
            return Fingerprints.of(event.scene(), event.bizId(), event.params());
        }
        String rendered = templateEngine.render(templateFor(sceneRegistry.find(event.scene())).content(),
                renderParams, RenderFormat.PLAIN);
        return Fingerprints.ofContent(event.scene(), rendered);
    }

    private Map<String, Object> renderParams(NotifyEvent event, Level level) {
        Map<String, Object> params = new LinkedHashMap<>(event.params());
        params.putIfAbsent("scene", event.scene());
        params.putIfAbsent("level", level.name());
        params.putIfAbsent("title", event.title() == null ? "" : event.title());
        params.putIfAbsent("bizId", event.bizId() == null ? "" : event.bizId());
        params.putIfAbsent("eventId", event.eventId());
        params.putIfAbsent("occurredAt", Instant.ofEpochMilli(event.occurredAt()).toString());
        params.putIfAbsent("instanceId", instanceId == null ? "" : instanceId);
        params.putIfAbsent("traceId", event.tag("traceId") == null ? "" : event.tag("traceId"));
        params.putIfAbsent("params", event.params());
        params.putIfAbsent("content", event.title() == null ? "" : event.title());
        return params;
    }

    /**
     * 把 trace 信息补进渲染参数（只在渲染前做，不影响去重指纹）。
     *
     * @param params 原始渲染参数
     * @param traceId traceId
     * @param spanId spanId
     * @return 新参数表
     */
    private static Map<String, Object> withTrace(Map<String, Object> params, String traceId, String spanId) {
        if (traceId == null && spanId == null) {
            return params;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(params);
        Object existingTrace = enriched.get("traceId");
        if (traceId != null && (existingTrace == null || String.valueOf(existingTrace).isBlank())) {
            enriched.put("traceId", traceId);
        }
        Object existingSpan = enriched.get("spanId");
        if (spanId != null && (existingSpan == null || String.valueOf(existingSpan).isBlank())) {
            enriched.put("spanId", spanId);
        }
        return enriched;
    }

    private String resolveTraceId(NotifyEvent event) {
        String tag = event.tag("traceId");
        return tag != null && !tag.isBlank() ? tag : traceContext.traceId();
    }

    private String resolveSpanId(NotifyEvent event) {
        String tag = event.tag("spanId");
        return tag != null && !tag.isBlank() ? tag : traceContext.spanId();
    }

    /**
     * 熔断配置。
     *
     * @param failureThreshold 连续失败阈值
     * @param openDuration 打开时长
     * @param maxOpenDuration 打开时长上限
     * @since 1.0
     */
    public record CircuitConfig(int failureThreshold, Duration openDuration, Duration maxOpenDuration) {

        /**
         * 默认配置：5 次失败、30s 打开、5min 上限。
         *
         * @return 配置
         */
        public static CircuitConfig defaults() {
            return new CircuitConfig(5, Duration.ofSeconds(30), Duration.ofMinutes(5));
        }
    }

    /**
     * {@link NotifyPipeline} 构建器。
     *
     * <p>@implNote 非线程安全，仅装配期使用。</p>
     *
     * @since 1.0
     */
    public static final class Builder {

        private SceneRegistry sceneRegistry;
        private List<EventFilter> filters = List.of();
        private ChannelRegistry channelRegistry;
        private TemplateEngine templateEngine;
        private Map<String, MessageTemplate> templates = Map.of();
        private DedupeStoreHolder dedupe;
        private FailureStore failureStore;
        private AuditRecorder auditRecorder = new LoggingAuditRecorder();
        private Masker masker;
        private NotifyMetrics metrics = NotifyMetrics.noop();
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private String instanceId = "unknown";
        private boolean maskBeforeAudit = true;
        private DeferredHandler deferredHandler;
        private CircuitConfig circuitConfig = CircuitConfig.defaults();
        private TraceContext traceContext = TraceContext.none();
        private EventAggregator aggregator;
        private EscalationPolicy escalation;

        private Builder() {
        }

        public Builder sceneRegistry(SceneRegistry sceneRegistry) {
            this.sceneRegistry = sceneRegistry;
            return this;
        }

        public Builder filters(List<EventFilter> filters) {
            this.filters = filters == null ? List.of() : filters;
            return this;
        }

        public Builder channelRegistry(ChannelRegistry channelRegistry) {
            this.channelRegistry = channelRegistry;
            return this;
        }

        public Builder templateEngine(TemplateEngine templateEngine) {
            this.templateEngine = templateEngine;
            return this;
        }

        public Builder templates(Map<String, MessageTemplate> templates) {
            this.templates = templates == null ? Map.of() : templates;
            return this;
        }

        public Builder dedupe(io.github.pkyit.notifyx.core.spi.DedupeStore store) {
            this.dedupe = new DedupeStoreHolder(store);
            return this;
        }

        public Builder failureStore(FailureStore failureStore) {
            this.failureStore = failureStore;
            return this;
        }

        public Builder auditRecorder(AuditRecorder auditRecorder) {
            this.auditRecorder = auditRecorder;
            return this;
        }

        public Builder masker(Masker masker) {
            this.masker = masker;
            return this;
        }

        public Builder metrics(NotifyMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder maskBeforeAudit(boolean maskBeforeAudit) {
            this.maskBeforeAudit = maskBeforeAudit;
            return this;
        }

        public Builder deferredHandler(DeferredHandler deferredHandler) {
            this.deferredHandler = deferredHandler;
            return this;
        }

        public Builder circuitConfig(CircuitConfig circuitConfig) {
            this.circuitConfig = circuitConfig;
            return this;
        }

        /**
         * trace 读取口，默认不采集；Spring 环境注入 MDC 实现。
         *
         * @param traceContext trace 读取口
         * @return 构建器
         */
        public Builder traceContext(TraceContext traceContext) {
            this.traceContext = traceContext;
            return this;
        }

        /**
         * 实例内聚合器；null 表示不去重后聚合。
         *
         * @param aggregator 聚合器
         * @return 构建器
         */
        public Builder aggregator(EventAggregator aggregator) {
            this.aggregator = aggregator;
            return this;
        }

        /**
         * 升级策略；null 表示不升级。
         *
         * @param escalation 升级策略
         * @return 构建器
         */
        public Builder escalation(EscalationPolicy escalation) {
            this.escalation = escalation;
            return this;
        }

        public NotifyPipeline build() {
            return new NotifyPipeline(this);
        }
    }
}
