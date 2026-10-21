package io.github.pkyit.notifyx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

class AlertDispatcherTest {

    @Test
    void deliversOnlyToSupportingChannels() {
        RecordingChannel warnOnly = new RecordingChannel("warn", event -> event.level() == AlertLevel.WARN);
        RecordingChannel errorOnly = new RecordingChannel("error", event -> event.level() == AlertLevel.ERROR);

        AlertDispatcher dispatcher = AlertDispatcher.builder()
                .channels(List.of(warnOnly, errorOnly))
                .build();

        dispatcher.dispatch(AlertEvent.builder(AlertLevel.WARN, "disk almost full").source("app").build());

        assertEquals(List.of("disk almost full"), warnOnly.titles());
        assertTrue(errorOnly.titles().isEmpty());
    }

    @Test
    void isolatesChannelFailures() {
        RecordingChannel failing = new RecordingChannel("boom", event -> true);
        failing.failWith = new IllegalStateException("webhook timeout");
        RecordingChannel healthy = new RecordingChannel("healthy", event -> true);
        List<FailedDelivery> failures = new ArrayList<>();

        AlertDispatcher dispatcher = AlertDispatcher.builder()
                .channels(List.of(failing, healthy))
                .failureHandler(failures::add)
                .build();

        dispatcher.dispatch(AlertEvent.builder(AlertLevel.ERROR, "payment failed").source("app").build());

        assertEquals(1, failures.size());
        assertEquals("boom", failures.get(0).channel());
        assertEquals(List.of("payment failed"), healthy.titles(), "healthy channel must still receive the event");
    }

    @Test
    void suppressesEventsOverRateLimit() {
        RecordingChannel channel = new RecordingChannel("all", event -> true);
        AlertDispatcher dispatcher = AlertDispatcher.builder()
                .channels(List.of(channel))
                .rateLimiter(new SlidingWindowRateLimiter(2, Duration.ofMinutes(1)))
                .build();

        for (int i = 0; i < 5; i++) {
            dispatcher.dispatch(AlertEvent.builder(AlertLevel.ERROR, "same error").source("app").build());
        }

        assertEquals(2, channel.titles().size());
    }

    @Test
    void appliesFilterBeforeDispatch() {
        RecordingChannel channel = new RecordingChannel("all", event -> true);
        AlertDispatcher dispatcher = AlertDispatcher.builder()
                .channels(List.of(channel))
                .filter(event -> event.level().atLeast(AlertLevel.ERROR))
                .build();

        dispatcher.dispatch(AlertEvent.builder(AlertLevel.INFO, "noise").source("app").build());
        dispatcher.dispatch(AlertEvent.builder(AlertLevel.CRITICAL, "down").source("app").build());

        assertEquals(List.of("down"), channel.titles());
    }

    @Test
    void honoursChannelOrder() {
        List<String> sequence = new CopyOnWriteArrayList<>();
        AlertChannel second = named("second", 20, sequence);
        AlertChannel first = named("first", 10, sequence);

        AlertDispatcher dispatcher = AlertDispatcher.builder()
                .channels(List.of(second, first))
                .build();
        dispatcher.dispatch(AlertEvent.builder(AlertLevel.ERROR, "order").source("app").build());

        assertEquals(List.of("first", "second"), sequence);
    }

    private static AlertChannel named(String name, int order, List<String> sequence) {
        return new AlertChannel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public void send(AlertEvent event) {
                sequence.add(name);
            }
        };
    }

    private static final class RecordingChannel implements AlertChannel {

        private final String name;
        private final java.util.function.Predicate<AlertEvent> support;
        private final List<String> titles = new ArrayList<>();
        private RuntimeException failWith;

        private RecordingChannel(String name, java.util.function.Predicate<AlertEvent> support) {
            this.name = name;
            this.support = support;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(AlertEvent event) {
            return support.test(event);
        }

        @Override
        public void send(AlertEvent event) {
            if (failWith != null) {
                throw failWith;
            }
            titles.add(event.title());
        }

        List<String> titles() {
            return titles;
        }
    }
}
