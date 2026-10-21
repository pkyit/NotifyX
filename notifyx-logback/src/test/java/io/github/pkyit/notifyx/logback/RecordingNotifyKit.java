package io.github.pkyit.notifyx.logback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.github.pkyit.notifyx.core.api.NotifyKit;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * 测试用门面：记录收到的全部事件，可选地在 send 内执行副作用（用于验证重入保护）。
 *
 * @since 1.0
 */
final class RecordingNotifyKit implements NotifyKit {

    private final List<NotifyEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstEvent = new CountDownLatch(1);
    private volatile Consumer<NotifyEvent> onSend = event -> { };
    private volatile long sendDelayMillis;

    @Override
    public void send(NotifyEvent event) {
        events.add(event);
        firstEvent.countDown();
        if (sendDelayMillis > 0) {
            try {
                Thread.sleep(sendDelayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        onSend.accept(event);
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

    List<NotifyEvent> events() {
        return events;
    }

    NotifyEvent awaitFirst(long timeoutMillis) throws InterruptedException {
        if (!firstEvent.await(timeoutMillis, TimeUnit.MILLISECONDS) && events.isEmpty()) {
            throw new AssertionError("no event submitted within " + timeoutMillis + "ms");
        }
        return events.get(0);
    }

    void awaitCount(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (events.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        if (events.size() < expected) {
            throw new AssertionError("expected " + expected + " events but got " + events.size());
        }
    }

    void onSend(Consumer<NotifyEvent> consumer) {
        this.onSend = consumer;
    }

    void delayEachSend(long millis) {
        this.sendDelayMillis = millis;
    }

    void reset() {
        events.clear();
        onSend = event -> { };
        sendDelayMillis = 0;
    }
}
