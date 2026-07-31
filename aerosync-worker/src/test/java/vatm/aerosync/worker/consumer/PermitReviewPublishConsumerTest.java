package vatm.aerosync.worker.consumer;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;
import vatm.aerosync.worker.service.PermitReviewPublishingService;

import java.time.LocalDateTime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PermitReviewPublishConsumerTest {

    @Test
    void failedPublishIsPersistedForAnExplicitRetry() {
        PermitReviewPublishingService service = mock(PermitReviewPublishingService.class);
        PermitReviewPublishConsumer consumer = new PermitReviewPublishConsumer(service);
        PermitReviewPublishCommand command = new PermitReviewPublishCommand(
                4L, "admin.one", LocalDateTime.now());
        doThrow(new IllegalStateException("ATFM unavailable"))
                .when(service).publish(command);

        consumer.onPublishRequested(command);

        verify(service).markFailed(command, "ATFM unavailable");
    }
}
