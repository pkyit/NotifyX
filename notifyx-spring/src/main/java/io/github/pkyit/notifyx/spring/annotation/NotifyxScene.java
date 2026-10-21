package io.github.pkyit.notifyx.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * 标记一个业务事件为 NotifyX 告警源（L3 用法，设计书 8.4）。
 *
 * <p>业务代码只发 Spring 事件，本注解告诉桥接层「翻译成哪个场景」：</p>
 *
 * <pre>{@code
 * @NotifyxScene(value = "ACCOUNT_CREATED", level = Level.INFO, receivers = {"ops@example.com"})
 * public record AccountCreatedEvent(String phone, String userName) {
 * }
 *
 * applicationEventPublisher.publishEvent(new AccountCreatedEvent(phone, userName));
 * }</pre>
 *
 * <p>如果不希望事件类依赖 NotifyX 注解，改用配置映射
 * {@code notifyx.events.mappings.<事件类全限定名>=SCENE_CODE}。</p>
 *
 * @since 1.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotifyxScene {

    /**
     * 场景码，必须匹配 {@code ^[A-Z][A-Z0-9_]{0,63}$}。
     *
     * @return 场景码
     */
    String value();

    /**
     * 事件级别。
     *
     * @return 级别，默认 WARN
     */
    Level level() default Level.WARN;

    /**
     * 指定接收人；为空时走场景配置。
     *
     * @return 接收人
     */
    String[] receivers() default {};
}
