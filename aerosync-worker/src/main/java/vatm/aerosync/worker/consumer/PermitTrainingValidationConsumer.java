package vatm.aerosync.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.PermitTrainingValidationCommand;
import vatm.aerosync.worker.service.PermitTrainingValidationService;

import java.util.NoSuchElementException;

@Component
public class PermitTrainingValidationConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PermitTrainingValidationConsumer.class);

    private final PermitTrainingValidationService validationService;

    public PermitTrainingValidationConsumer(
            PermitTrainingValidationService validationService) {
        this.validationService = validationService;
    }

    @RabbitListener(
            queues = "${app.rabbit.permit-training-validation-queue}")
    public void onValidationRequested(
            PermitTrainingValidationCommand command) {
        try {
            validationService.validate(command);
        } catch (NoSuchElementException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Failed to validate permit training candidate {}",
                    command.candidateId(),
                    exception);
            validationService.markFailed(command, exception.getMessage());
        }
    }
}
