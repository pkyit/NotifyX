package io.github.pkyit.notifyx.spring.aop;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.support.DurationParser;
import io.github.pkyit.notifyx.spring.annotation.AlarmProtect;
import io.github.pkyit.notifyx.spring.annotation.NotifyOn;
import io.github.pkyit.notifyx.spring.spel.SpelEvaluator;

/**
 * 注解切面。四条铁律（设计书 8.3）：
 * <ol>
 *   <li>切面内部任何异常不得外抛；</li>
 *   <li>SpEL 求值失败降级为不求值；</li>
 *   <li>{@code @AlarmProtect} 绝不吞异常；</li>
 *   <li>切面顺序在事务切面内层。</li>
 * </ol>
 *
 * <p>@implNote 无状态（仅持有不可变依赖），线程安全。</p>
 *
 * @since 1.0
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class NotifyxAspect {

    private static final Logger log = LoggerFactory.getLogger(NotifyxAspect.class);

    private final NotifyKit notifyKit;
    private final SpelEvaluator spelEvaluator;

    /**
     * 构造切面。
     *
     * @param notifyKit 门面
     * @param spelEvaluator 求值器
     */
    public NotifyxAspect(NotifyKit notifyKit, SpelEvaluator spelEvaluator) {
        this.notifyKit = notifyKit;
        this.spelEvaluator = spelEvaluator;
    }

    /**
     * 异常告警：异常原样抛出。
     *
     * @param joinPoint 切点
     * @param alarmProtect 注解
     * @return 业务返回值
     * @throws Throwable 业务异常原样抛出
     */
    @Around("@annotation(alarmProtect)")
    public Object aroundAlarm(ProceedingJoinPoint joinPoint, AlarmProtect alarmProtect) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Throwable error) {
            try {
                NotifyEvent event = buildEvent(alarmProtect.scene(), alarmProtect.level(), alarmProtect.bizId(),
                        alarmProtect.silent(), alarmProtect.receivers(), new String[0], joinPoint, error);
                notifyKit.send(event);
            } catch (Throwable ignored) {
                log.warn("notifyx alarm advice failed silently: {}", ignored.toString());
            }
            throw error;
        }
    }

    /**
     * 返回值判定或异常告警：返回值与异常均原样透传。
     *
     * @param joinPoint 切点
     * @param notifyOn 注解
     * @return 业务返回值
     * @throws Throwable 业务异常原样抛出
     */
    @Around("@annotation(notifyOn)")
    public Object aroundNotify(ProceedingJoinPoint joinPoint, NotifyOn notifyOn) throws Throwable {
        Object result = null;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
        } catch (Throwable throwable) {
            error = throwable;
        }
        try {
            boolean shouldNotify = error != null;
            if (!shouldNotify && !notifyOn.when().isBlank()) {
                Object evaluated = spelEvaluator.evaluateAfter(notifyOn.when(), joinPoint, result, null);
                shouldNotify = Boolean.TRUE.equals(evaluated);
            }
            if (shouldNotify) {
                NotifyEvent event = buildEvent(notifyOn.scene(), notifyOn.level(), notifyOn.bizId(),
                        notifyOn.dedupe(), notifyOn.receivers(), notifyOn.context(), joinPoint, error);
                notifyKit.send(event);
            }
        } catch (Throwable ignored) {
            log.warn("notifyx notify advice failed silently: {}", ignored.toString());
        }
        if (error != null) {
            throw error;
        }
        return result;
    }

    private NotifyEvent buildEvent(String scene, Level level, String bizIdExpression, String silentExpression,
                                   String[] receivers, String[] contextExpressions, ProceedingJoinPoint joinPoint,
                                   Throwable error) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (error != null) {
            params.put("exception", error.getClass().getSimpleName());
            params.put("exceptionMessage", String.valueOf(error.getMessage()));
        }
        if (joinPoint.getArgs().length > 0) {
            params.put("args", joinPoint.getArgs());
        }
        for (int i = 0; i < contextExpressions.length; i++) {
            Object value = spelEvaluator.evaluateBefore(contextExpressions[i], joinPoint);
            if (value != null) {
                params.put("ctx" + i, value);
            }
        }

        NotifyEvent.Builder builder = NotifyEvent.builder(scene).level(level).params(params);
        String bizId = stringOf(spelEvaluator.evaluateBefore(bizIdExpression, joinPoint));
        if (!bizId.isBlank()) {
            builder.bizId(bizId);
        }
        Duration silent = DurationParser.parse(silentExpression);
        if (!silent.isZero()) {
            builder.silentWindow(silent);
        }
        if (receivers.length > 0) {
            builder.receivers(java.util.Arrays.asList(receivers));
        }
        return builder.build();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
