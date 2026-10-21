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
import io.github.pkyit.notifyx.core.support.Fingerprints;
import io.github.pkyit.notifyx.core.support.LoggingAuditRecorder;

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
     * @param event 事件
     */
    public void handle(NotifyEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            metrics.eventReceived(event, "L4");
            SceneConfig scene = sceneRegistry.find(event.scene());
            Level level = event.level().atLeast(scene.level()) ? event.level() : scene.level();
            Duration silentWindow = resolveSilentWindow(event, scene);
            Map<String, Object> renderParams = renderParams(event, level);
            String dedupeKey = resolveDedupeKey(event, renderParams);

            FilterContext filterContext = new FilterContext(event, scene, Instant.now(), dedupeKey, silentWindow);
            FilterVerdict verdict = runFilters(event, filterContext);
            if (verdict.decision() == FilterVerdict.Decision.REJECT) {
                reject(event, verdict.reason());
                return;
            }
            if (verdict.decision() == FilterVerdict.Decision.DEFER) {
                defer(event, verdict);
                return;
            }
            dispatch(event, scene, level, renderParams, dedupeKey);
        } catch (RuntimeException ex) {
            log.warn("notifyx pipeline failed for scene={} eventId={}: {}", event.scene(), event.eventId(), ex.toString());
        }
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

    private void dispatch(NotifyEvent event, SceneConfig scene, Level level,
                          Map<String, Object> renderParams, String dedupeKey) {
        List<String> receivers = resolveReceivers(event, scene);
        List<NotifyChannel> channels = resolveChannels(scene);
        if (channels.isEmpty()) {
            log.debug("no channel available for scene={}", event.scene());
            return;
        }
        boolean failed = false;
        for (NotifyChannel channel : channels) {
            if (channel.supportsBatch() && receivers.size() > 1) {
                failed |= sendOne(channel, event, renderParams, null, dedupeKey, scene);
            } else if (receivers.isEmpty()) {
                failed |= sendOne(channel, event, renderParams, null, dedupeKey, scene);
            } else {
                for (String receiver : receivers) {
                    failed |= sendOne(channel, event, renderParams, receiver, dedupeKey, scene);
                }
            }
        }
        if (failed && dedupe != null && dedupe.store() != null) {
            dedupe.store().release(dedupeKey);
        }
    }

    private boolean sendOne(NotifyChannel channel, NotifyEvent event, Map<String, Object> renderParams,
                            String receiver, String dedupeKey, SceneConfig scene) {
        String renderedTitle = templateEngine.render(templateFor(scene).title(), renderParams, channel.preferredFormat());
        String renderedContent = templateEngine.render(templateFor(scene).content(), renderParams, channel.preferredFormat());
        NotifyContext baseContext = NotifyContext.builder(event)
                .renderedTitle(renderedTitle)
                .renderedContent(renderedContent)
                .renderFormat(channel.preferredFormat())
                .receiver(receiver)
                .instanceId(instanceId)
                .idempotencyKey(dedupeKey)
                .build();
        if (!channel.isEnabled(baseContext)) {
            return false;
        }

        CircuitBreaker breaker = breakerFor(channel.name());
        int maxAttempts = retryPolicy.attemptsFor(channel);
        if (scene.retry() > 0) {
            maxAttempts = Math.min(maxAttempts, scene.retry());
        }
        long started = System.nanoTime();
        SendResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!breaker.allowRequest()) {
                result = SendResult.suppressed(SendResult.Status.CIRCUIT_OPEN, "channel circuit open");
                break;
            }
            NotifyContext context = baseContext.withAttempt(attempt);
            try {
                result = channel.send(context);
                breaker.recordSuccess();
                break;
            } catch (ChannelException ex) {
                breaker.recordFailure();
                boolean retryable = isRetryable(channel, ex);
                if (!retryable || attempt >= maxAttempts) {
                    result = retryable
                            ? SendResult.retryable(ex.getMessage(), Duration.ofNanos(System.nanoTime() - started))
                            : SendResult.nonRetryable(ex.getMessage(), Duration.ofNanos(System.nanoTime() - started));
                    break;
                }
                sleep(retryPolicy.delayAfter(attempt, channel));
            }
        }
        Duration cost = result == null ? Duration.ZERO : result.cost();
        audit(event, channel.name(), receiver, result, cost);
        metrics.sendResult(channel.name(), event.scene(), result == null ? null : result.status(), cost);
        boolean failed = result != null && (result.status() == SendResult.Status.FAIL_RETRYABLE
                || result.status() == SendResult.Status.FAIL_NON_RETRYABLE
                || result.status() == SendResult.Status.CIRCUIT_OPEN);
        if (failed) {
            persistFailure(event, channel.name(), result, maxAttempts);
            notifyOnError(event, result);
        } else if (result != null && result.status() == SendResult.Status.SUCCESS) {
            notifyOnSuccess(event);
        }
        return failed;
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

    private void persistFailure(NotifyEvent event, String channel, SendResult result, int attempts) {
        if (failureStore == null) {
            return;
        }
        try {
            FailedRecord record = new FailedRecord(event.eventId(), RecordStatus.PENDING, 0, event.eventId(),
                    channel, String.valueOf(result.failureReason()), attempts, System.currentTimeMillis(),
                    System.currentTimeMillis() + 60_000L);
            failureStore.save(record);
            log.warn("event persisted for compensation eventId={} channel={}", event.eventId(), channel);
        } catch (RuntimeException ex) {
            log.warn("failed to persist failure record: {}", ex.toString());
        }
    }

    private void audit(NotifyEvent event, String channel, String receiver, SendResult result, Duration cost) {
        if (auditRecorder == null) {
            return;
        }
        String maskedReceiver = receiver;
        if (maskBeforeAudit && receiver != null && masker != null) {
            maskedReceiver = masker.maskText(receiver);
        }
        try {
            auditRecorder.record(new AuditRecord(event.eventId(), event.scene(), event.level().name(),
                    event.bizId(), channel, maskedReceiver,
                    result == null ? "UNKNOWN" : result.status().name(),
                    result == null ? null : result.failureReason(),
                    result == null ? 0 : 1, cost.toMillis(), instanceId,
                    event.tag("traceId"), System.currentTimeMillis(),
                    result != null && result.status() == SendResult.Status.SUCCESS
                            ? Long.valueOf(System.currentTimeMillis()) : null));
        } catch (RuntimeException ex) {
            log.debug("audit failed: {}", ex.toString());
        }
    }

    private void reject(NotifyEvent event, String reason) {
        log.debug("event rejected: scene={} reason={}", event.scene(), reason);
        audit(event, "-", null,
                SendResult.suppressed(statusFor(reason), reason), Duration.ZERO);
    }

    private void defer(NotifyEvent event, FilterVerdict verdict) {
        if (deferredHandler == null || verdict.deferUntil() == null) {
            reject(event, "deferred-unsupported");
            return;
        }
        deferredHandler.defer(event, verdict.deferUntil());
        audit(event, "-", null, SendResult.suppressed(SendResult.Status.SUPPRESSED, "deferred"), Duration.ZERO);
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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

        public NotifyPipeline build() {
            return new NotifyPipeline(this);
        }
    }
}