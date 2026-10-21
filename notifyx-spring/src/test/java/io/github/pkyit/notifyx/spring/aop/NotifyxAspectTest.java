package io.github.pkyit.notifyx.spring.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.spring.annotation.AlarmProtect;
import io.github.pkyit.notifyx.spring.annotation.NotifyOn;
import io.github.pkyit.notifyx.spring.spel.SpelEvaluator;

/**
 * {@code @AlarmProtect} / {@code @NotifyOn} 切面测试（设计书 §8.3 四条铁律）：
 * 异常原样抛出、成功不误报、条件判定、context 表达式、SpEL 默认禁方法调用。
 *
 * @since 1.0
 */
class NotifyxAspectTest {

    private static final RecordingKit KIT = new RecordingKit();

    private AnnotationConfigApplicationContext context;
    private TestService service;

    @BeforeEach
    void setUp() {
        KIT.reset();
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(TestService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("@AlarmProtect：异常原样抛出，同时发一条 ERROR 告警（含 bizId/silent/receivers）")
    void shouldAlarmAndRethrow() {
        assertThatThrownBy(() -> service.fail("SO1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(KIT.events).hasSize(1);
        NotifyEvent event = KIT.events.get(0);
        assertThat(event.scene()).isEqualTo("ORDER_FAIL");
        assertThat(event.level()).isEqualTo(Level.ERROR);
        assertThat(event.bizId()).isEqualTo("SO1");
        assertThat(event.silentWindow()).hasSeconds(30);
        assertThat(event.receivers()).containsExactly("ops@example.com");
        assertThat(event.params()).containsEntry("exception", "IllegalStateException");
    }

    @Test
    @DisplayName("@AlarmProtect：正常返回不发告警")
    void shouldNotAlarmOnSuccess() {
        assertThat(service.ok("SO1")).isEqualTo("ok");
        assertThat(KIT.events).isEmpty();
    }

    @Test
    @DisplayName("@NotifyOn：条件为真才发告警，返回值原样透传")
    void shouldNotifyWhenConditionTrue() {
        assertThat(service.check("bad1")).isEqualTo("bad1");
        assertThat(KIT.events).hasSize(1);
        assertThat(KIT.events.get(0).scene()).isEqualTo("COND");
        assertThat(KIT.events.get(0).params()).containsEntry("ctx0", "bad1");

        assertThat(service.check("good")).isEqualTo("good");
        assertThat(KIT.events).hasSize(1);
    }

    @Test
    @DisplayName("@NotifyOn：方法抛异常时发告警并原样抛出")
    void shouldNotifyOnException() {
        assertThatThrownBy(() -> service.throwIt("SO9")).isInstanceOf(IllegalArgumentException.class);

        assertThat(KIT.events).hasSize(1);
        assertThat(KIT.events.get(0).scene()).isEqualTo("EXC");
        assertThat(KIT.events.get(0).level()).isEqualTo(Level.WARN);
        assertThat(KIT.events.get(0).params()).containsEntry("exception", "IllegalArgumentException");
    }

    @Test
    @DisplayName("默认禁止 SpEL 方法调用：toString() 表达式求值失败 → 不告警、不影响业务")
    void shouldBlockSpelMethodInvocationByDefault() {
        assertThat(service.methodExpr("x")).isEqualTo("x");
        assertThat(KIT.events).isEmpty();
    }

    @Test
    @DisplayName("显式开启 allow-method-invocation 后表达式可用，但需自行承担注入风险")
    void shouldAllowMethodInvocationWhenOptIn() {
        KIT.reset();
        try (AnnotationConfigApplicationContext permissive =
                     new AnnotationConfigApplicationContext(PermissiveConfig.class)) {
            TestService relaxed = permissive.getBean(TestService.class);
            assertThat(relaxed.methodExpr("x")).isEqualTo("x");
            assertThat(KIT.events).hasSize(1);
            assertThat(KIT.events.get(0).scene()).isEqualTo("METHOD");
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class PermissiveConfig {

        @Bean
        NotifyKit notifyKit() {
            return KIT;
        }

        @Bean
        SpelEvaluator spelEvaluator() {
            return new SpelEvaluator(true);
        }

        @Bean
        NotifyxAspect notifyxAspect(NotifyKit notifyKit, SpelEvaluator spelEvaluator) {
            return new NotifyxAspect(notifyKit, spelEvaluator);
        }

        @Bean
        TestService testService() {
            return new TestService();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {

        @Bean
        NotifyKit notifyKit() {
            return KIT;
        }

        @Bean
        SpelEvaluator spelEvaluator() {
            return new SpelEvaluator(false);
        }

        @Bean
        NotifyxAspect notifyxAspect(NotifyKit notifyKit, SpelEvaluator spelEvaluator) {
            return new NotifyxAspect(notifyKit, spelEvaluator);
        }

        @Bean
        TestService testService() {
            return new TestService();
        }
    }

    static class TestService {

        @AlarmProtect(scene = "ORDER_FAIL", bizId = "#orderNo", silent = "30s", receivers = "ops@example.com")
        public String fail(String orderNo) {
            throw new IllegalStateException("boom");
        }

        @AlarmProtect(scene = "NEVER")
        public String ok(String orderNo) {
            return "ok";
        }

        @NotifyOn(scene = "COND", when = "#r != null and #r == 'bad1'", context = "#orderNo")
        public String check(String orderNo) {
            return orderNo;
        }

        @NotifyOn(scene = "EXC")
        public String throwIt(String orderNo) {
            throw new IllegalArgumentException("bad");
        }

        @NotifyOn(scene = "METHOD", when = "#r.toString() != ''")
        public String methodExpr(String orderNo) {
            return orderNo;
        }
    }

    static final class RecordingKit implements NotifyKit {

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

        void reset() {
            events.clear();
        }
    }
}
