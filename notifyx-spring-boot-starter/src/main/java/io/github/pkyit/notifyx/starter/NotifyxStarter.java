package io.github.pkyit.notifyx.starter;

/**
 * Starter 的标记类型。
 *
 * <p>它没有行为，存在的唯一目的是：让这个「空聚合模块」在发布时能产出
 * {@code -sources.jar} 与 {@code -javadoc.jar}——Maven Central 对 jar packaging
 * 强制要求这两个附件，而 javadoc 工具在没有可文档化类型时会直接报错。</p>
 *
 * <p>不要引用本类，也不要在这里加任何逻辑。业务代码请依赖
 * {@code io.github.pkyit.notifyx.core.api.NotifyKit}。</p>
 *
 * @since 1.0
 */
public final class NotifyxStarter {

    private NotifyxStarter() {
        throw new AssertionError("no instances");
    }
}
