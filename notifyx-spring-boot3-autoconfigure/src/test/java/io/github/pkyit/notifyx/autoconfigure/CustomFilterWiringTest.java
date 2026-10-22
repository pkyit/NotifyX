package io.github.pkyit.notifyx.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.pkyit.notifyx.channel.log.LogChannelAutoConfiguration;
import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEvent;
import io.github.pkyit.notifyx.core.spi.EventFilter;
import io.github.pkyit.notifyx.core.spi.FilterContext;
import io.github.pkyit.notifyx.core.spi.FilterVerdict;

/**
 * 用户自定义 {@link EventFilter} 装配测试（设计书 §9.2 的 900+ 扩展点）。
 *
 * @since 1.0
 */
class CustomFilterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotifyxAutoConfiguration.class, LogChannelAutoConfiguration.class))
            .withPropertyValues(
                    "notifyx.async.enabled=false",
                    "notifyx.scenes.SCENE_A.channels=log",
                    "notifyx.scenes.SCENE_A.level=ERROR");

    @Test
    @DisplayName("用户 EventFilter Bean 自动进入责任链，并按 order 升序执行")
    void shouldWireCustomFiltersInOrder() {
        List<String> calls = new CopyOnWriteArrayList<>();
        runner.withBean("lateFilter", EventFilter.class, () -> filter("late", 960, calls))
                .withBean("earlyFilter", EventFilter.class, () -> filter("early", 950, calls))
                .run(context -> {
                    context.getBean(NotifyKit.class).send(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build());
                    assertThat(calls).containsExactly("early", "late");
                });
    }

    private static EventFilter filter(String name, int order, List<String> calls) {
        return new EventFilter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public FilterVerdict doFilter(FilterContext ctx) {
                calls.add(name);
                return FilterVerdict.pass();
            }
        };
    }
}
