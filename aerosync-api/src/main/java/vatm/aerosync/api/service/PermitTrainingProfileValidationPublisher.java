package vatm.aerosync.api.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.api.config.RabbitMqProperties;
import vatm.aerosync.common.dto.PermitTrainingProfileValidationCommand;

@Service
public class PermitTrainingProfileValidationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties properties;

    public PermitTrainingProfileValidationPublisher(
            RabbitTemplate rabbitTemplate,
            RabbitMqProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(PermitTrainingProfileValidationCommand command) {
        rabbitTemplate.convertAndSend(
                properties.getPermitProfileValidationExchange(),
                properties.getPermitProfileValidationRoutingKey(),
                command);
    }
}
