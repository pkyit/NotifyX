package io.github.pkyit.notifyx.core.spi;

import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 事件过滤器：责任链节点，漏斗的每一环都是一个 Filter。
 *
 * <p>顺序固定为：全局静默(100) → 场景路由(200) → 免打扰(300) → 去重(400)
 * → 场景限流(500) → 全局限流(600) → 用户自定义(900+)。</p>
 *
 * <p>@implNote 实现类必须线程安全，可能被多条投递线程并发调用。</p>
 *
 * @since 1.0
 */
public interface EventFilter extends NotifyxOrdered {

    /**
     * 过滤器名，用于诊断输出与指标标签。
     *
     * @return 名称
     */
    String name();

    /**
     * 执行过滤。
     *
     * @param ctx 过滤上下文
     * @return 裁决；不得返回 null
     */
    FilterVerdict doFilter(FilterContext ctx);

    /**
     * 该过滤器是否对当前事件生效。
     *
     * @param event 事件
     * @return 是否生效
     */
    default boolean supports(NotifyEvent event) {
        return true;
    }
}
