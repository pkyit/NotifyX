package io.github.pkyit.notifyx.spring.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.spring.annotation.NotifyxScene;

/**
 * L3 Spring Event 桥接测试（设计书 8.4）：注解翻译、配置映射、__source 追溯、异常吞掉。
 *
 * @since 1.0
 */
class NotifyxEventBridgeTest {

    private final RecordingNotifyKit kit = new RecordingNotifyKit();
    private final NotifyxEventBridge bridge = new NotifyxEventBridge(kit, Map.of(
            PlainEvent.class.getName(), "PLAIN_SCENE"));

    @Test
    @DisplayName("@NotifyxScene 事件被翻译成对应场景，并合并 payload 参数")
    void shouldTranslateAnnotatedEvent() {
        bridge.onEvent(new AccountCreatedEvent("13800000000", "张三"));

        assertThat(kit.events).hasSize(1);
        NotifyEvent event = kit.events.get(0);
        assertThat(event.scene()).isEqualTo("ACCOUNT_CREATED");
        assertThat(event.level()).isEqualTo(Level.INFO);
        assertThat(event.receivers()).containsExactly("ops@example.com");
        assertThat(event.params()).containsEntry("phone", "13800000000");
        assertThat(String.valueOf(event.params().get("__source")))
                .contains(AccountCreatedEvent.class.getName())
                .contains("张三");
    }

    @Test
    @DisplayName("配置映射让事件类零依赖 NotifyX")
    void shouldTranslateMappedEvent() {
        bridge.onEvent(new PlainEvent("SO-1"));

        assertThat(kit.events).hasSize(1);
        NotifyEvent event = kit.events.get(0);
        assertThat(event.scene()).isEqualTo("PLAIN_SCENE");
        assertThat(event.level()).isEqualTo(Level.WARN);
        assertThat(String.valueOf(event.params().get("__source"))).contains("SO-1");
    }

    @Test
    @DisplayName("未注解且未映射的事件被安静忽略")
    void shouldIgnoreUnknownEvent() {
        bridge.onEvent(new Object());

        assertThat(kit.events).isEmpty();
    }

    @Test
    @DisplayName("门面抛异常时不把异常抛回事件发布方")
    void shouldSwallowNotifyKitFailure() {
        NotifyKit failing = new RecordingNotifyKit() {
            @Override
            public void send(NotifyEvent event) {
                throw new IllegalStateException("boom");
            }
        };
        NotifyxEventBridge failingBridge = new NotifyxEventBridge(failing, Map.of());

        assertThatCode(() -> failingBridge.onEvent(new AccountCreatedEvent("1", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("@EventListener 能收到 ApplicationContext 发布的事件")
    void shouldListenToSpringEvents() {
        new ApplicationContextRunner()
                .withBean(NotifyKit.class, () -> kit)
                .withBean(NotifyxEventBridge.class, () -> new NotifyxEventBridge(kit, Map.of()))
                .run(context -> {
                    context.publishEvent(new AccountCreatedEvent("13900000000", "李四"));
                    assertThat(kit.events).hasSize(1);
                    assertThat(kit.events.get(0).scene()).isEqualTo("ACCOUNT_CREATED");
                });
    }

    @Test
    @DisplayName("子类继承父类上的 @NotifyxScene")
    void shouldInheritAnnotationFromSuperclass() {
        bridge.onEvent(new SubAccountEvent("13700000000"));

        assertThat(kit.events).hasSize(1);
        assertThat(kit.events.get(0).scene()).isEqualTo("ACCOUNT_CREATED");
    }

    @NotifyxScene(value = "ACCOUNT_CREATED", level = Level.INFO, receivers = {"ops@example.com"})
    static class AccountCreatedEvent implements NotifyxEventPayload {

        private final String phone;
        private final String userName;

        AccountCreatedEvent(String phone, String userName) {
            this.phone = phone;
            this.userName = userName;
        }

        @Override
        public Map<String, ?> params() {
            return Map.of("phone", phone);
        }

        @Override
        public String toString() {
            return "AccountCreatedEvent{userName='" + userName + "'}";
        }
    }

    static class SubAccountEvent extends AccountCreatedEvent {

        SubAccountEvent(String phone) {
            super(phone, "sub");
        }
    }

    static class PlainEvent {

        private final String orderId;

        PlainEvent(String orderId) {
            this.orderId = orderId;
        }

        @Override
        public String toString() {
            return "PlainEvent{orderId='" + orderId + "'}";
        }
    }

    static class RecordingNotifyKit implements NotifyKit {

        final List<NotifyEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void send(NotifyEvent event) {
            events.add(event);
        }

        @Override
        public void send(String scene, Map<String, ?> params) {
            send(NotifyEvent.builder(scene).params(params).build());
        }

        @Override
        public void send(String scene, String receiver, Map<String, ?> params) {
            send(NotifyEvent.builder(scene).receiver(receiver).params(params).build());
        }

        @Override
        public void alarm(Throwable throwable) {
            send(NotifyEvent.builder("UNCAUGHT_EXCEPTION").build());
        }

        @Override
        public void alarm(String scene, Throwable throwable, Object... context) {
            send(NotifyEvent.builder(scene).build());
        }

        @Override
        public void sendAsync(NotifyEvent event) {
            send(event);
        }
    }
}
