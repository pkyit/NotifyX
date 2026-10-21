package io.github.pkyit.notifyx.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * 方法抛异常即告警。切面只观察异常，绝不吞掉异常（设计书 8.3 铁律 3）。
 *
 * <p>@implNote 注解不可变，线程安全。</p>
 *
 * @since 1.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AlarmProtect {

    /**
     * 场景码。
     *
     * @return 场景码
     */
    String scene();

    /**
     * 级别。
     *
     * @return 级别，默认 ERROR
     */
    Level level() default Level.ERROR;

    /**
     * 静默期，如 {@code 10m}、{@code 30s}；空串表示使用配置默认值。
     *
     * @return 静默期表达式
     */
    String silent() default "";

    /**
     * SpEL 表达式，取业务主键，如 {@code #cmd.orderNo}。
     *
     * @return 表达式
     */
    String bizId() default "";

    /**
     * 覆盖接收人。
     *
     * @return 接收人
     */
    String[] receivers() default {};
}