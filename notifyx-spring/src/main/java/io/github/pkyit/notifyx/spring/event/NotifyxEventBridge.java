package io.github.pkyit.notifyx.spring.event;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.spring.annotation.NotifyxScene;

/**
 * L3 Spring Event 桥接（设计书 8.4）：把业务事件统一翻译成 {@link NotifyEvent}。
 *
 * <p>业务代码只 {@code publishEvent}，不需要注入 NotifyKit。场景来源有两种：</p>
 * <ol>
 *   <li>事件类上的 {@link NotifyxScene} 注解；</li>
 *   <li>配置映射 {@code notifyx.events.mappings.<事件类全限定名>=SCENE_CODE}（事件类零依赖 NotifyX）。</li>
 * </ol>
 *
 * <p>翻译时把「原始事件类名 + toString()」放进 {@code params.__source}，便于审计追溯；
 * 事件实现 {@link NotifyxEventPayload} 时再合并其结构化参数。</p>
 *
 * <p>@implNote 线程安全。监听方法吞掉一切异常：翻译失败绝不影响业务的事件发布。
 * 线程级重入保护防止「NotifyX 自身的 Spring 事件」形成回路。</p>
 *
 * @since 1.0
 */
public final class NotifyxEventBridge {

    private static final Logger log = LoggerFactory.getLogger(NotifyxEventBridge.class);

    private static final int MAX_SOURCE_CHARS = 2000;

    private static final ThreadLocal<Boolean> IN_TRANSLATION = new ThreadLocal<>();

    private final NotifyKit notifyKit;
    private final Map<String, String> mappings;

    /**
     * 构造桥接器。
     *
     * @param notifyKit 门面
     * @param mappings 事件类全限定名 → 场景码；可为 null
     */
    public NotifyxEventBridge(NotifyKit notifyKit, Map<String, String> mappings) {
        this.notifyKit = java.util.Objects.requireNonNull(notifyKit, "notifyKit");
        this.mappings = mappings == null ? Map.of() : Map.copyOf(mappings);
    }

    /**
     * 翻译并投递；未标注/未映射的事件直接忽略。
     *
     * @param event Spring 事件（任意对象）
     */
    @EventListener
    public void onEvent(Object event) {
        if (event == null || event instanceof NotifyEvent) {
            return;
        }
        Resolved resolved = resolve(event.getClass());
        if (resolved == null) {
            return;
        }
        if (Boolean.TRUE.equals(IN_TRANSLATION.get())) {
            return;
        }
        IN_TRANSLATION.set(Boolean.TRUE);
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("__source", sourceOf(event));
            if (event instanceof NotifyxEventPayload payload) {
                payload.params().forEach((key, value) -> {
                    if (key != null) {
                        params.put(key, value);
                    }
                });
            }
            NotifyEvent.Builder builder = NotifyEvent.builder(resolved.scene())
                    .level(resolved.level())
                    .params(params);
            for (String receiver : resolved.receivers()) {
                builder.receiver(receiver);
            }
            notifyKit.send(builder.build());
        } catch (Throwable ex) {
            // 桥接层绝不能把异常抛回事件发布方（业务线程）
            log.warn("notifyx failed to translate Spring event {}: {}",
                    event.getClass().getName(), ex.toString());
        } finally {
            IN_TRANSLATION.remove();
        }
    }

    private Resolved resolve(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            NotifyxScene annotation = current.getAnnotation(NotifyxScene.class);
            if (annotation != null) {
                return new Resolved(annotation.value(), annotation.level(), annotation.receivers());
            }
            String scene = mappings.get(current.getName());
            if (scene != null && !scene.isBlank()) {
                return new Resolved(scene, Level.WARN, new String[0]);
            }
        }
        return null;
    }

    private static String sourceOf(Object event) {
        String text;
        try {
            text = String.valueOf(event);
        } catch (RuntimeException ex) {
            text = "<toString failed: " + ex.getClass().getSimpleName() + ">";
        }
        String source = event.getClass().getName() + "#" + text;
        return source.length() <= MAX_SOURCE_CHARS ? source : source.substring(0, MAX_SOURCE_CHARS) + "…";
    }

    /**
     * 事件解析结果。
     *
     * @implNote 不可变。
     */
    private record Resolved(String scene, Level level, String[] receivers) {

        private Resolved {
            receivers = receivers == null ? new String[0] : receivers.clone();
        }

        @Override
        public String[] receivers() {
            return receivers.clone();
        }

        @Override
        public String toString() {
            return "Resolved{scene='" + scene + "', level=" + level
                    + ", receivers=" + Arrays.toString(receivers) + '}';
        }
    }
}
