package vatm.aerosync.ingest.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.ingest.config.RabbitMqProperties;

@Service
public class IngestPublisher {

    static final int PRIORITY_LEVEL = 10;

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    public IngestPublisher(RabbitTemplate rabbitTemplate, RabbitMqProperties rabbitMqProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    public void publish(FileIngestedEvent event) {
        String exchange = rabbitMqProperties.getFileIngestedExchange();
        String routingKey = rabbitMqProperties.getFileProcessingRoutingKey();
        DebugSessionLog.log("B", "IngestPublisher.java:publish", "publishing to rabbit",
                DebugSessionLog.map("exchange", exchange, "routingKey", routingKey,
                        "syncJobId", event.getSyncJobId()));
        rabbitTemplate.convertAndSend(
                exchange,
                routingKey,
                event,
                message -> {
                    if (event.isPriority()) {
                        message.getMessageProperties().setPriority(PRIORITY_LEVEL);
                    }
                    return message;
                });
    }
}
