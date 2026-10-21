package io.github.pkyit.notifyx.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * 返回值判定或抛异常时告警。返回值原样返回，异常原样抛出（设计书 8.3）。
 *
 * <p>@implNote 注解不可变，线程安全。</p>
 *
 * @since 1.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotifyOn {

    /**
     * 场景码。
     *
     * @return 场景码
     */
    String scene();

    /**
     * 级别。
     *
     * @return 级别
     */
    Level level() default Level.WARN;

    /**
     * SpEL 判定表达式，如 {@code #r.code != 0}；空串表示仅在抛异常时触发。
     *
     * @return 表达式
     */
    String when() default "";

    /**
     * 静默期，如 {@code 5m}。
     *
     * @return 静默期表达式
     */
    String dedupe() default "";

    /**
     * SpEL 表达式，取业务主键。
     *
     * @return 表达式
     */
    String bizId() default "";

    /**
     * SpEL 表达式列表，求值结果摊平进 params。
     *
     * @return 表达式列表
     */
    String[] context() default {};

    /**
     * 覆盖接收人。
     *
     * @return 接收人
     */
    String[] receivers() default {};
}
