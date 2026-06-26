package vatm.aerosync.ingest.email;

import java.time.LocalDateTime;
import java.util.List;

public record EmailMessage(
        String messageId,
        String sender,
        String subject,
        LocalDateTime receivedAt,
        List<EmailAttachment> attachments,
        boolean priority,
        String body
) {
}
