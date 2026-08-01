package vatm.aerosync.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.PermitTrainingProfileCanaryCommand;
import vatm.aerosync.worker.service.PermitTrainingProfileCanaryService;

import java.util.NoSuchElementException;

@Component
public class PermitTrainingProfileCanaryConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PermitTrainingProfileCanaryConsumer.class);

    private final PermitTrainingProfileCanaryService canaryService;

    public PermitTrainingProfileCanaryConsumer(
            PermitTrainingProfileCanaryService canaryService) {
        this.canaryService = canaryService;
    }

    @RabbitListener(queues = "${app.rabbit.permit-profile-canary-queue}")
    public void onCanaryRequested(
            PermitTrainingProfileCanaryCommand command) {
        try {
            canaryService.evaluate(command);
        } catch (NoSuchElementException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Failed to evaluate permit profile canary {}",
                    command.evidenceId(),
                    exception);
            canaryService.markFailed(command, exception.getMessage());
        }
    }
}
