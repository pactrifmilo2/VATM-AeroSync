package vatm.aerosync.api.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.api.config.RabbitMqProperties;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;

@Service
public class PermitReviewCommandPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties properties;

    public PermitReviewCommandPublisher(RabbitTemplate rabbitTemplate,
                                        RabbitMqProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(PermitReviewPublishCommand command) {
        rabbitTemplate.convertAndSend(
                properties.getPermitReviewPublishExchange(),
                properties.getPermitReviewPublishRoutingKey(),
                command);
    }
}
