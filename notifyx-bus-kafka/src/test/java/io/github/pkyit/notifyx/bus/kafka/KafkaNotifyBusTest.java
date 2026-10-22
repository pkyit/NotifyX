package io.github.pkyit.notifyx.bus.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.pkyit.notifyx.core.exception.BusException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * Kafka 总线单元测试：key=dedupeKey、value=报文、失败抛 BusException。
 *
 * @since 1.0
 */
@SuppressWarnings("unchecked")
class KafkaNotifyBusTest {

    private final Producer<String, String> producer = mock(Producer.class);

    @Test
    @DisplayName("发送：key=dedupeKey，value 为报文 JSON")
    void shouldSendWithDedupeKey() throws Exception {
        Future<RecordMetadata> future = CompletableFuture.completedFuture(null);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        KafkaNotifyBus bus = new KafkaNotifyBus(producer, "notifyx-events", Duration.ofSeconds(1));
        NotifyEnvelope envelope = envelope();

        bus.publish(envelope);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("notifyx-events");
        assertThat(record.key()).isEqualTo(envelope.dedupeKey());
        assertThat(record.value()).contains("\"scene\":\"SCENE_A\"");
    }

    @Test
    @DisplayName("发送失败：包装为 BusException")
    void shouldWrapFailure() throws Exception {
        Future<RecordMetadata> future = mock(Future.class);
        when(future.get(anyLong(), any())).thenThrow(new ExecutionException(new RuntimeException("down")));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        KafkaNotifyBus bus = new KafkaNotifyBus(producer, "notifyx-events", Duration.ofSeconds(1));

        assertThatThrownBy(() -> bus.publish(envelope())).isInstanceOf(BusException.class);
    }

    @Test
    @DisplayName("close 关闭 producer")
    void shouldCloseProducer() {
        new KafkaNotifyBus(producer, "t", Duration.ofSeconds(1)).close();

        verify(producer).close(any(Duration.class));
    }

    private static NotifyEnvelope envelope() {
        return NotifyEnvelope.of(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build(),
                "sha256:abc", null, null, "inst", 0, 1L);
    }
}
