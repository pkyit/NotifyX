package io.github.pkyit.notifyx.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告警调度器：把 {@link AlertEvent} 分发给所有“愿意接收”的 {@link AlertChannel}。
 *
 * <p>职责边界很窄，只做四件事：</p>
 * <ol>
 *   <li>按过滤条件裁剪事件；</li>
 *   <li>按限流器抑制重复事件；</li>
 *   <li>把投递任务交给 {@link Executor}，不阻塞业务线程；</li>
 *   <li>隔离单个通道的异常，保证一个通道失败不影响其它通道。</li>
 * </ol>
 *
 * <p>本类不依赖 Spring，可独立用于非 Spring 场景。</p>
 */
public final class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final List<AlertChannel> channels;
    private final Executor executor;
    private final AlertRateLimiter rateLimiter;
    private final Predicate<AlertEvent> filter;
    private final Consumer<FailedDelivery> failureHandler;

    private AlertDispatcher(Builder builder) {
        this.channels = List.copyOf(builder.channels);
        this.executor = builder.executor;
        this.rateLimiter = builder.rateLimiter;
        this.filter = builder.filter;
        this.failureHandler = builder.failureHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 异步分发一条告警。调用方线程只做过滤与入队，不会等待通道投递完成。
     */
    public void dispatch(AlertEvent event) {
        Objects.requireNonNull(event, "event");
        if (!filter.test(event)) {
            log.trace("alert filtered out: {}", event);
            return;
        }
        if (rateLimiter != null && !rateLimiter.tryAcquire(event.fingerprint())) {
            log.debug("alert suppressed by rate limiter: fingerprint={}", event.fingerprint());
            return;
        }

        List<AlertChannel> targets = channels.stream()
                .filter(channel -> channel.supports(event))
                .sorted(Comparator.comparingInt(AlertChannel::order))
                .toList();
        if (targets.isEmpty()) {
            log.debug("no channel accepts alert: level={}, title={}", event.level(), event.title());
            return;
        }

        try {
            executor.execute(() -> deliver(targets, event));
        } catch (RejectedExecutionException ex) {
            // 队列满时退化为调用方线程同步投递，保证告警不丢
            log.warn("alert executor rejected task, delivering on caller thread: {}", ex.toString());
            deliver(targets, event);
        }
    }

    private void deliver(List<AlertChannel> targets, AlertEvent event) {
        for (AlertChannel channel : targets) {
            try {
                channel.send(event);
            } catch (Exception ex) {
                handleFailure(channel, event, ex);
            } catch (Throwable error) {
                handleFailure(channel, event, error);
            }
        }
    }

    private void handleFailure(AlertChannel channel, AlertEvent event, Throwable cause) {
        log.warn("alert channel [{}] failed to deliver event [{}]: {}", channel.name(), event.title(), cause.toString());
        if (failureHandler != null) {
            try {
                failureHandler.accept(new FailedDelivery(channel.name(), event, cause));
            } catch (RuntimeException ex) {
                log.warn("alert failure handler threw: {}", ex.toString());
            }
        }
    }

    /**
     * 已注册通道名称（按注册顺序）。
     */
    public List<String> channelNames() {
        List<String> names = new ArrayList<>(channels.size());
        for (AlertChannel channel : channels) {
            names.add(channel.name());
        }
        return names;
    }

    /**
     * {@link AlertDispatcher} 构建器。
     */
    public static final class Builder {

        private List<AlertChannel> channels = List.of();
        private Executor executor = Runnable::run;
        private AlertRateLimiter rateLimiter;
        private Predicate<AlertEvent> filter = event -> true;
        private Consumer<FailedDelivery> failureHandler;

        private Builder() {
        }

        public Builder channels(List<? extends AlertChannel> channels) {
            this.channels = channels == null ? List.of() : List.copyOf(channels);
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public Builder rateLimiter(AlertRateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        public Builder filter(Predicate<AlertEvent> filter) {
            this.filter = filter == null ? event -> true : filter;
            return this;
        }

        public Builder failureHandler(Consumer<FailedDelivery> failureHandler) {
            this.failureHandler = failureHandler;
            return this;
        }

        public AlertDispatcher build() {
            return new AlertDispatcher(this);
        }
    }
}
