package vatm.aerosync.ingest.email;

public record EmailReference(
        String messageId,
        String mailboxFolder,
        long uidValidity,
        long messageUid
) {
}
