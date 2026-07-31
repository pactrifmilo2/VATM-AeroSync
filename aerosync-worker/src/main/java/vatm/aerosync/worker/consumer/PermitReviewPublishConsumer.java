package vatm.aerosync.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;
import vatm.aerosync.worker.service.PermitReviewPublishingService;

@Component
public class PermitReviewPublishConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermitReviewPublishConsumer.class);

    private final PermitReviewPublishingService publishingService;

    public PermitReviewPublishConsumer(PermitReviewPublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @RabbitListener(queues = "${app.rabbit.permit-review-publish-queue}")
    public void onPublishRequested(PermitReviewPublishCommand command) {
        try {
            publishingService.publish(command);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to publish permit review {}", command.reviewId(), exception);
            publishingService.markFailed(command, exception.getMessage());
        }
    }
}
