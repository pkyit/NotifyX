package io.github.pkyit.notifyx.sample;

import java.util.Map;

import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.spring.annotation.NotifyxScene;
import io.github.pkyit.notifyx.spring.event.NotifyxEventPayload;

/**
 * L3 示例事件：业务只发事件，不注入 NotifyKit（设计书 8.4）。
 *
 * @param phone 手机号
 * @param userName 用户名
 * @since 1.0
 */
@NotifyxScene(value = "ACCOUNT_CREATED", level = Level.INFO)
public record AccountCreatedEvent(String phone, String userName) implements NotifyxEventPayload {

    @Override
    public Map<String, ?> params() {
        return Map.of("phone", phone, "userName", userName);
    }
}
