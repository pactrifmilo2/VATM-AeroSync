package vatm.aerosync.api.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.api.config.RabbitMqProperties;
import vatm.aerosync.common.dto.PermitTrainingProfileCanaryCommand;

@Service
public class PermitTrainingProfileCanaryPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties properties;

    public PermitTrainingProfileCanaryPublisher(
            RabbitTemplate rabbitTemplate,
            RabbitMqProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(PermitTrainingProfileCanaryCommand command) {
        rabbitTemplate.convertAndSend(
                properties.getPermitProfileCanaryExchange(),
                properties.getPermitProfileCanaryRoutingKey(),
                command);
    }
}
