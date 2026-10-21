package io.github.pkyit.notifyx.spring.event;

import java.util.Map;

/**
 * 可选扩展：让业务事件自己提供模板参数（L3 用法）。
 *
 * <p>不实现本接口也能用：桥接层会把事件类名与 {@code toString()} 放进
 * {@code params.__source}。实现本接口可以给出结构化参数，避免解析 toString。</p>
 *
 * @since 1.0
 */
public interface NotifyxEventPayload {

    /**
     * 模板参数。
     *
     * @return 参数表，不可为 null
     */
    Map<String, ?> params();
}
