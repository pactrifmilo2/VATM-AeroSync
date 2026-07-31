package vatm.aerosync.api.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.api.config.RabbitMqProperties;
import vatm.aerosync.common.dto.PermitTrainingValidationCommand;

@Service
public class PermitTrainingValidationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties properties;

    public PermitTrainingValidationPublisher(
            RabbitTemplate rabbitTemplate,
            RabbitMqProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(PermitTrainingValidationCommand command) {
        rabbitTemplate.convertAndSend(
                properties.getPermitTrainingValidationExchange(),
                properties.getPermitTrainingValidationRoutingKey(),
                command);
    }
}
