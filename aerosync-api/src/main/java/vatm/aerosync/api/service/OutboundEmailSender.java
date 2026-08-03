package vatm.aerosync.api.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface OutboundEmailSender {

    SendResult send(OutboundEmail email);

    record OutboundEmail(
            String recipient,
            String subject,
            String body,
            List<Attachment> attachments,
            Map<String, String> headers
    ) {
    }

    record Attachment(Path path, String fileName) {
    }

    record SendResult(String messageId, String recipient) {
    }
}
