package vatm.aerosync.ingest.email;

import java.time.LocalDateTime;

public record EmailEnvelope(
        String messageId,
        String sender,
        String subject,
        LocalDateTime receivedAt,
        String mailboxFolder,
        long uidValidity,
        long messageUid,
        boolean priority
) {
    public EmailReference reference() {
        return new EmailReference(messageId, mailboxFolder, uidValidity, messageUid);
    }
}
