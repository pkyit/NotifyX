package io.github.pkyit.notifyx.logback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.github.pkyit.notifyx.core.api.NotifyKit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.spi.AppenderAttachable;

/**
 * Spring 绑定器：把容器里的 {@link NotifyKit} 注入到已挂载的 {@link NotifyxAppender}（设计书 8.1）。
 *
 * <p>为什么需要它：Appender 由 logback 在 Spring 上下文刷新<b>之前</b>创建，拿不到 Bean；
 * 因此由本绑定器在单例全部实例化之后回填门面，并在容器关闭时解绑。</p>
 *
 * <p>遍历会递归进入 {@code AsyncAppender} 等 {@link AppenderAttachable} 包装器，
 * 用户把 NOTIFYX 包在异步 appender 里也能绑定成功。</p>
 *
 * <p>@implNote 线程安全（装配期单线程调用；运行期只读）。</p>
 *
 * @since 1.0
 */
public final class NotifyxAppenderBinder implements SmartInitializingSingleton, DisposableBean {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(NotifyxAppenderBinder.class);

    private final NotifyKit notifyKit;
    private volatile int boundAppenders;

    /**
     * 构造绑定器。
     *
     * @param notifyKit 门面，不可为 null
     */
    public NotifyxAppenderBinder(NotifyKit notifyKit) {
        this.notifyKit = java.util.Objects.requireNonNull(notifyKit, "notifyKit");
    }

    /**
     * 上下文就绪后回填门面。
     */
    @Override
    public void afterSingletonsInstantiated() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            log.debug("notifyx: logging backend is not logback ({}), appender binding skipped",
                    factory == null ? "null" : factory.getClass().getName());
            return;
        }
        int bound = bind(context, notifyKit);
        this.boundAppenders = bound;
        if (bound == 0) {
            log.debug("notifyx: no NotifyxAppender found in logback context, nothing to bind");
        } else {
            log.info("notifyx: bound {} logback appender(s) to NotifyKit", bound);
        }
    }

    /**
     * 容器关闭时解绑，避免 appender 持有已销毁的 Bean。
     */
    @Override
    public void destroy() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            bind(context, null);
        }
        this.boundAppenders = 0;
    }

    /**
     * 最近一次绑定成功的 appender 数量。
     *
     * @return 数量
     */
    public int getBoundAppenders() {
        return boundAppenders;
    }

    private static int bind(LoggerContext context, NotifyKit kit) {
        Set<Appender<ILoggingEvent>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Logger> loggers = new ArrayList<>(context.getLoggerList());
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root != null && !loggers.contains(root)) {
            loggers.add(root);
        }
        int bound = 0;
        for (Logger logger : loggers) {
            bound += bindAttachable(logger, visited, kit);
        }
        return bound;
    }

    private static int bindAttachable(AppenderAttachable<ILoggingEvent> attachable,
                                      Set<Appender<ILoggingEvent>> visited, NotifyKit kit) {
        int bound = 0;
        Iterator<Appender<ILoggingEvent>> iterator = attachable.iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> appender = iterator.next();
            if (appender == null || !visited.add(appender)) {
                continue;
            }
            if (appender instanceof NotifyxAppender notifyxAppender) {
                notifyxAppender.setNotifyKit(kit);
                bound++;
            } else if (appender instanceof AppenderAttachable<?> nested) {
                @SuppressWarnings("unchecked")
                AppenderAttachable<ILoggingEvent> casted = (AppenderAttachable<ILoggingEvent>) nested;
                bound += bindAttachable(casted, visited, kit);
            }
        }
        return bound;
    }
}
