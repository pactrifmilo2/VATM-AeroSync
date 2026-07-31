package vatm.aerosync.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.PermitTrainingProfileValidationCommand;
import vatm.aerosync.worker.service.PermitTrainingProfileValidationService;

import java.util.NoSuchElementException;

@Component
public class PermitTrainingProfileValidationConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PermitTrainingProfileValidationConsumer.class);

    private final PermitTrainingProfileValidationService validationService;

    public PermitTrainingProfileValidationConsumer(
            PermitTrainingProfileValidationService validationService) {
        this.validationService = validationService;
    }

    @RabbitListener(
            queues = "${app.rabbit.permit-profile-validation-queue}")
    public void onValidationRequested(
            PermitTrainingProfileValidationCommand command) {
        try {
            validationService.validate(command);
        } catch (NoSuchElementException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Failed to validate learned permit profile {}",
                    command.profileId(),
                    exception);
            validationService.markFailed(command, exception.getMessage());
        }
    }
}
