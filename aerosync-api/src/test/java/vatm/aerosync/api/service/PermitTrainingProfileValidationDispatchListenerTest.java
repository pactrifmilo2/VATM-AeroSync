package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.PermitTrainingProfileValidationCommand;

import java.time.LocalDateTime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PermitTrainingProfileValidationDispatchListenerTest {

    @Test
    void publishesOnlyAfterTheTransactionalListenerIsInvoked() {
        PermitTrainingProfileValidationPublisher publisher =
                mock(PermitTrainingProfileValidationPublisher.class);
        PermitTrainingProfileValidationApiService service =
                mock(PermitTrainingProfileValidationApiService.class);
        PermitTrainingProfileValidationDispatchListener listener =
                new PermitTrainingProfileValidationDispatchListener(
                        publisher, service);
        PermitTrainingProfileValidationCommand command = command();

        listener.dispatch(command);

        verify(publisher).publish(command);
    }

    @Test
    void returnsProfileToEvidenceCollectionWhenQueueingFails() {
        PermitTrainingProfileValidationPublisher publisher =
                mock(PermitTrainingProfileValidationPublisher.class);
        PermitTrainingProfileValidationApiService service =
                mock(PermitTrainingProfileValidationApiService.class);
        PermitTrainingProfileValidationDispatchListener listener =
                new PermitTrainingProfileValidationDispatchListener(
                        publisher, service);
        PermitTrainingProfileValidationCommand command = command();
        RuntimeException failure = new RuntimeException("broker unavailable");
        doThrow(failure).when(publisher).publish(command);

        listener.dispatch(command);

        verify(service).markQueueFailed(command, failure);
    }

    private PermitTrainingProfileValidationCommand command() {
        return new PermitTrainingProfileValidationCommand(
                7L,
                "a".repeat(64),
                "operator.one",
                LocalDateTime.of(2026, 7, 31, 17, 0));
    }
}
