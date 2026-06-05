package vatm.aerosync.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import vatm.aerosync.common.dto.SyncResultEvent;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.worker.config.RabbitMqProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SyncResultPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Captor
    private ArgumentCaptor<SyncResultEvent> eventCaptor;

    private SyncResultPublisher publisher;

    @BeforeEach
    void setUp() {
        RabbitMqProperties properties = new RabbitMqProperties();
        properties.setSyncResultExchange("sync.result");
        publisher = new SyncResultPublisher(rabbitTemplate, properties);
    }

    @Test
    void publish_sendsToSyncResultFanoutExchange() {
        publisher.publish(7L, SyncStatus.SUCCESS, AlertLevel.INFO, "done");

        verify(rabbitTemplate).convertAndSend(eq("sync.result"), eq(""), eventCaptor.capture());
        SyncResultEvent event = eventCaptor.getValue();
        assertThat(event.getSyncJobId()).isEqualTo(7L);
        assertThat(event.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(event.getAlertLevel()).isEqualTo(AlertLevel.INFO);
        assertThat(event.getMessage()).isEqualTo("done");
        assertThat(event.getTimestamp()).isNotNull();
    }
}
