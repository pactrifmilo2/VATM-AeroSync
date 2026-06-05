package vatm.aerosync.worker.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.dto.SyncResultEvent;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.worker.config.RabbitMqProperties;

import java.time.LocalDateTime;

@Service
public class SyncResultPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    public SyncResultPublisher(RabbitTemplate rabbitTemplate, RabbitMqProperties rabbitMqProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    public void publish(Long syncJobId, SyncStatus status, AlertLevel alertLevel, String message) {
        SyncResultEvent event = new SyncResultEvent(
                syncJobId, status, alertLevel, message, LocalDateTime.now());
        rabbitTemplate.convertAndSend(rabbitMqProperties.getSyncResultExchange(), "", event);
    }
}
