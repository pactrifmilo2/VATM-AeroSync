package vatm.aerosync.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vatm.aerosync.common.dto.PermitTrainingProfileValidationCommand;

@Component
public class PermitTrainingProfileValidationDispatchListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PermitTrainingProfileValidationDispatchListener.class);

    private final PermitTrainingProfileValidationPublisher publisher;
    private final PermitTrainingProfileValidationApiService validationService;

    public PermitTrainingProfileValidationDispatchListener(
            PermitTrainingProfileValidationPublisher publisher,
            PermitTrainingProfileValidationApiService validationService) {
        this.publisher = publisher;
        this.validationService = validationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(PermitTrainingProfileValidationCommand command) {
        try {
            publisher.publish(command);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Could not queue learned permit profile validation {}",
                    command.profileId(),
                    exception);
            validationService.markQueueFailed(command, exception);
        }
    }
}
