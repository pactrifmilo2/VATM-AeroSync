package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.PermitTrainingProfileCanaryCommand;

import java.time.LocalDateTime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PermitTrainingProfileCanaryDispatchListenerTest {

    @Test
    void publishesOnlyAfterTheTransactionalListenerIsInvoked() {
        PermitTrainingProfileCanaryPublisher publisher =
                mock(PermitTrainingProfileCanaryPublisher.class);
        PermitTrainingProfileCanaryApiService service =
                mock(PermitTrainingProfileCanaryApiService.class);
        PermitTrainingProfileCanaryDispatchListener listener =
                new PermitTrainingProfileCanaryDispatchListener(
                        publisher, service);
        PermitTrainingProfileCanaryCommand command = command();

        listener.dispatch(command);

        verify(publisher).publish(command);
    }

    @Test
    void removesThePendingCanaryWhenQueueingFails() {
        PermitTrainingProfileCanaryPublisher publisher =
                mock(PermitTrainingProfileCanaryPublisher.class);
        PermitTrainingProfileCanaryApiService service =
                mock(PermitTrainingProfileCanaryApiService.class);
        PermitTrainingProfileCanaryDispatchListener listener =
                new PermitTrainingProfileCanaryDispatchListener(
                        publisher, service);
        PermitTrainingProfileCanaryCommand command = command();
        RuntimeException failure = new RuntimeException("broker unavailable");
        doThrow(failure).when(publisher).publish(command);

        listener.dispatch(command);

        verify(service).markQueueFailed(command, failure);
    }

    private PermitTrainingProfileCanaryCommand command() {
        return new PermitTrainingProfileCanaryCommand(
                7L,
                19L,
                "a".repeat(64),
                "operator.one",
                LocalDateTime.of(2026, 7, 31, 17, 0));
    }
}
