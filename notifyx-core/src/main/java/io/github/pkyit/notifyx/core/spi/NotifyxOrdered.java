package io.github.pkyit.notifyx.core.spi;

/**
 * 可排序扩展点的统一契约。
 *
 * <p>设计书在附录 A 中写作 {@code extends Ordered}，但 core 禁止依赖 Spring
 * （设计书 ADR-003），因此这里定义 core 自有的排序契约；Spring 装配时会同时识别
 * {@code org.springframework.core.Ordered} 并按最低值优先排序。</p>
 *
 * <p>@implNote 无状态接口，实现类需保证线程安全。</p>
 *
 * @since 1.0
 */
public interface NotifyxOrdered {

    /** 框架保留的过滤器起始序号，用户自定义过滤器建议从 900 开始。 */
    int USER_FILTER_ORDER = 900;

    /**
     * 排序值，越小越先执行。
     *
     * @return 排序值
     */
    default int order() {
        return 0;
    }
}
