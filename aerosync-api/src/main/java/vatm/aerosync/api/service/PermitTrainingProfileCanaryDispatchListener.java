package vatm.aerosync.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vatm.aerosync.common.dto.PermitTrainingProfileCanaryCommand;

@Component
public class PermitTrainingProfileCanaryDispatchListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PermitTrainingProfileCanaryDispatchListener.class);

    private final PermitTrainingProfileCanaryPublisher publisher;
    private final PermitTrainingProfileCanaryApiService canaryService;

    public PermitTrainingProfileCanaryDispatchListener(
            PermitTrainingProfileCanaryPublisher publisher,
            PermitTrainingProfileCanaryApiService canaryService) {
        this.publisher = publisher;
        this.canaryService = canaryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(PermitTrainingProfileCanaryCommand command) {
        try {
            publisher.publish(command);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Could not queue permit profile canary {}",
                    command.evidenceId(),
                    exception);
            canaryService.markQueueFailed(command, exception);
        }
    }
}
