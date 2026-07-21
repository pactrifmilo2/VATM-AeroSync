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
        String body,
        String mailboxFolder,
        long uidValidity,
        long messageUid
) {
    public EmailMessage(String messageId,
                        String sender,
                        String subject,
                        LocalDateTime receivedAt,
                        List<EmailAttachment> attachments,
                        boolean priority,
                        String body) {
        this(messageId, sender, subject, receivedAt, attachments, priority, body, "INBOX", 0L, 0L);
    }

    public EmailEnvelope envelope() {
        return new EmailEnvelope(
                messageId, sender, subject, receivedAt, mailboxFolder, uidValidity, messageUid, priority);
    }

    public EmailReference reference() {
        return envelope().reference();
    }
}
