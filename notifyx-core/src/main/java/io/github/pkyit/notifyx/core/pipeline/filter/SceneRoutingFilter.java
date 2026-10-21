package io.github.pkyit.notifyx.core.pipeline.filter;

import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;

/**
 * 场景路由（漏斗第 2 环，order=200）：场景被禁用时拦截。
 *
 * <p>场景 → 通道列表的解析在管线进入责任链前完成，本过滤器只负责「场景是否启用」的裁决，
 * 保证禁用场景不产生任何发送与聚合。</p>
 *
 * <p>@implNote 无状态，线程安全。</p>
 *
 * @since 1.0
 */
public final class SceneRoutingFilter implements EventFilter {

    /** 框架保留序号。 */
    public static final int ORDER = 200;

    @Override
    public String name() {
        return "scene-routing";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public FilterVerdict doFilter(FilterContext ctx) {
        if (!ctx.sceneConfig().enabled()) {
            return FilterVerdict.reject("scene-disabled");
        }
        return FilterVerdict.pass();
    }
}