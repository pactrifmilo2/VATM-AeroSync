package vatm.aerosync.api.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.api.config.RabbitMqProperties;
import vatm.aerosync.common.dto.FileIngestedEvent;

@Service
public class JobRetryPublisher {

    static final int PRIORITY_LEVEL = 10;

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    public JobRetryPublisher(RabbitTemplate rabbitTemplate, RabbitMqProperties rabbitMqProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    public void publish(FileIngestedEvent event) {
        rabbitTemplate.convertAndSend(
                rabbitMqProperties.getFileIngestedExchange(),
                rabbitMqProperties.getFileProcessingRoutingKey(),
                event,
                message -> {
                    if (event.isPriority()) {
                        message.getMessageProperties().setPriority(PRIORITY_LEVEL);
                    }
                    return message;
                });
    }
}
