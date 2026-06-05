package vatm.aerosync.ingest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.ingest.config.RabbitMqProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Captor
    private ArgumentCaptor<MessagePostProcessor> postProcessorCaptor;

    private IngestPublisher ingestPublisher;

    @BeforeEach
    void setUp() {
        RabbitMqProperties properties = new RabbitMqProperties();
        properties.setFileIngestedExchange("file.ingested");
        properties.setFileProcessingRoutingKey("file.processing");
        ingestPublisher = new IngestPublisher(rabbitTemplate, properties);
    }

    @Test
    void publish_sendsEventToConfiguredExchangeAndRoutingKey() {
        FileIngestedEvent event = new FileIngestedEvent(
                42L, "/tmp/flight.csv", "hash-1", FileSourceType.FILESYSTEM, false);

        ingestPublisher.publish(event);

        verify(rabbitTemplate).convertAndSend(
                eq("file.ingested"),
                eq("file.processing"),
                eq(event),
                postProcessorCaptor.capture());
    }

    @Test
    void publish_setsHighPriorityWhenEventIsPriority() throws Exception {
        FileIngestedEvent event = new FileIngestedEvent(
                1L, "/tmp/vip.csv", "hash-vip", FileSourceType.EMAIL, true);

        ingestPublisher.publish(event);

        verify(rabbitTemplate).convertAndSend(
                eq("file.ingested"),
                eq("file.processing"),
                eq(event),
                postProcessorCaptor.capture());

        MessagePostProcessor processor = postProcessorCaptor.getValue();
        Message message = new Message(new ObjectMapper().writeValueAsBytes(event));
        Message processed = processor.postProcessMessage(message);

        assertThat(processed.getMessageProperties().getPriority()).isEqualTo(10);
    }
}
