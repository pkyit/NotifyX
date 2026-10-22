package io.github.pkyit.notifyx.bus.rabbitmq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import io.github.pkyit.notifyx.core.exception.BusException;
import io.github.pkyit.notifyx.core.model.Level;
import io.github.pkyit.notifyx.core.model.NotifyEnvelope;
import io.github.pkyit.notifyx.core.model.NotifyEvent;

/**
 * RabbitMQ 总线单元测试：persistent message、messageId、confirm、失败包装。
 *
 * @since 1.0
 */
@SuppressWarnings("unchecked")
class RabbitMqNotifyBusTest {

    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);

    private RabbitMqNotifyBus bus() throws Exception {
        RabbitMqBusProperties properties = new RabbitMqBusProperties();
        properties.setQueue("notifyx-events");
        when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.isOpen()).thenReturn(true);
        when(connection.isOpen()).thenReturn(true);
        when(channel.queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(mock(AMQP.Queue.DeclareOk.class));
        return new RabbitMqNotifyBus(properties, factory);
    }

    @Test
    @DisplayName("发布：durable 队列、persistent、messageId=dedupeKey、等待 confirm")
    void shouldPublishPersistentMessage() throws Exception {
        RabbitMqNotifyBus bus = bus();

        bus.publish(envelope());

        ArgumentCaptor<AMQP.BasicProperties> captor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        verify(channel).basicPublish(eq(""), eq("notifyx-events"), captor.capture(), any(byte[].class));
        verify(channel).waitForConfirmsOrDie(anyLong());
        AMQP.BasicProperties messageProperties = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(messageProperties.getDeliveryMode()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(messageProperties.getMessageId()).isEqualTo("sha256:rabbit");
        org.assertj.core.api.Assertions.assertThat(messageProperties.getContentType()).isEqualTo("application/json");
        bus.close();
    }

    @Test
    @DisplayName("publish 失败包装为 BusException")
    void shouldWrapPublishFailure() throws Exception {
        RabbitMqNotifyBus bus = bus();
        doThrow(new IOException("down")).when(channel).basicPublish(anyString(), anyString(), any(), any(byte[].class));

        assertThatThrownBy(() -> bus.publish(envelope())).isInstanceOf(BusException.class);
        bus.close();
    }

    private static NotifyEnvelope envelope() {
        return NotifyEnvelope.of(NotifyEvent.builder("SCENE_A").level(Level.ERROR).build(),
                "sha256:rabbit", null, null, "inst", 0, 1L);
    }
}
