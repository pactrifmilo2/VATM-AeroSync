package vatm.aerosync.api.dto;

public record EmailResendResponse(
        String originalMessageId,
        String sentMessageId,
        String recipient,
        int attachmentsSent,
        int attachmentsSkipped,
        boolean accepted
) {
}
