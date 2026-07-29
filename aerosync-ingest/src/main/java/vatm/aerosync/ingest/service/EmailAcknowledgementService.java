package vatm.aerosync.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.dto.SyncResultEvent;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.ingest.email.EmailClient;
import vatm.aerosync.ingest.email.EmailDisposition;
import vatm.aerosync.ingest.email.EmailEnvelope;
import vatm.aerosync.ingest.email.EmailReference;
import vatm.aerosync.ingest.config.EmailProperties;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

@Service
public class EmailAcknowledgementService {

    private static final Logger log = LoggerFactory.getLogger(EmailAcknowledgementService.class);
    private static final EnumSet<EmailProcessingStatus> TERMINAL_STATUSES = EnumSet.of(
            EmailProcessingStatus.SAVED,
            EmailProcessingStatus.FAILED,
            EmailProcessingStatus.QUARANTINED,
            EmailProcessingStatus.SKIPPED,
            EmailProcessingStatus.NO_ATTACHMENT,
            EmailProcessingStatus.BLOCKED);
    private static final EnumSet<EmailProcessingStatus> SUCCESS_STATUSES = EnumSet.of(
            EmailProcessingStatus.SAVED,
            EmailProcessingStatus.SKIPPED);

    private final EmailMetadataRepository emailMetadataRepository;
    private final EmailClient emailClient;
    private final EmailProperties emailProperties;

    public EmailAcknowledgementService(EmailMetadataRepository emailMetadataRepository,
                                       EmailClient emailClient,
                                       EmailProperties emailProperties) {
        this.emailMetadataRepository = emailMetadataRepository;
        this.emailClient = emailClient;
        this.emailProperties = emailProperties;
    }

    public boolean shouldDownload(EmailEnvelope envelope) {
        if (emailMetadataRepository
                .existsByMailboxFolderAndUidValidityAndMessageUidAndIngestCompleteTrue(
                        envelope.mailboxFolder(), envelope.uidValidity(), envelope.messageUid())) {
            return false;
        }
        return !emailMetadataRepository.existsByMessageIdAndIngestCompleteTrue(envelope.messageId());
    }

    public void markIngestComplete(EmailReference reference) {
        List<EmailMetadata> records = findMessageRecords(reference);
        records.forEach(record -> record.setIngestComplete(true));
        emailMetadataRepository.saveAll(records);
        acknowledgeIfComplete(reference);
    }

    @RabbitListener(queues = "${app.rabbit.email-acknowledgement-queue}")
    public void onSyncResult(SyncResultEvent event) {
        emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(event.getSyncJobId()).ifPresent(metadata -> {
            metadata.setProcessingStatus(toEmailStatus(event.getStatus()));
            emailMetadataRepository.save(metadata);
            acknowledgeIfComplete(toReference(metadata));
        });
    }

    public void retryPendingAcknowledgements() {
        List<EmailMetadata> pending = emailMetadataRepository.findByAcknowledgementStatusIn(List.of(
                EmailAcknowledgementStatus.PENDING,
                EmailAcknowledgementStatus.FAILED));
        pending.stream()
                .filter(this::hasMailboxIdentity)
                .map(this::toReference)
                .distinct()
                .forEach(this::acknowledgeIfComplete);
    }

    void acknowledgeIfComplete(EmailReference reference) {
        if (!emailProperties.isAcknowledgementEnabled()) {
            return;
        }
        List<EmailMetadata> records = findMessageRecords(reference);
        if (records.isEmpty() || records.stream().anyMatch(record -> !record.isIngestComplete())) {
            return;
        }
        int expectedAttachments = records.stream()
                .mapToInt(EmailMetadata::getAttachmentCount)
                .max()
                .orElse(0);
        if (expectedAttachments > 0 && records.size() < expectedAttachments) {
            return;
        }
        if (records.stream().map(EmailMetadata::getProcessingStatus).anyMatch(status -> !TERMINAL_STATUSES.contains(status))) {
            return;
        }
        EmailDisposition disposition = records.stream()
                .map(EmailMetadata::getProcessingStatus)
                .allMatch(SUCCESS_STATUSES::contains)
                ? EmailDisposition.PROCESSED
                : EmailDisposition.ERROR;
        EmailAcknowledgementStatus desiredStatus = disposition == EmailDisposition.PROCESSED
                ? EmailAcknowledgementStatus.MOVED_PROCESSED
                : EmailAcknowledgementStatus.MOVED_ERROR;
        if (records.stream().allMatch(record -> record.getAcknowledgementStatus() == desiredStatus)) {
            return;
        }
        try {
            emailClient.acknowledge(reference, disposition);
            LocalDateTime acknowledgedAt = LocalDateTime.now();
            records.forEach(record -> {
                record.setAcknowledgementStatus(desiredStatus);
                record.setAcknowledgedAt(acknowledgedAt);
                record.setAcknowledgementError(null);
            });
        } catch (RuntimeException exception) {
            String error = truncate(exception.getMessage());
            records.forEach(record -> {
                record.setAcknowledgementStatus(EmailAcknowledgementStatus.FAILED);
                record.setAcknowledgementError(error);
            });
            log.warn("Email acknowledgement failed for {}: {}", reference.messageId(), exception.getMessage());
        }
        emailMetadataRepository.saveAll(records);
    }

    private List<EmailMetadata> findMessageRecords(EmailReference reference) {
        return emailMetadataRepository.findByMailboxFolderAndUidValidityAndMessageUid(
                reference.mailboxFolder(), reference.uidValidity(), reference.messageUid());
    }

    private EmailProcessingStatus toEmailStatus(SyncStatus status) {
        return switch (status) {
            case SUCCESS -> EmailProcessingStatus.SAVED;
            case FAILED -> EmailProcessingStatus.FAILED;
            case QUARANTINED -> EmailProcessingStatus.QUARANTINED;
            case SKIPPED -> EmailProcessingStatus.SKIPPED;
            case PENDING -> EmailProcessingStatus.DOWNLOADED;
            case IN_PROGRESS -> EmailProcessingStatus.PROCESSING;
        };
    }

    private boolean hasMailboxIdentity(EmailMetadata metadata) {
        return metadata.getMailboxFolder() != null
                && metadata.getUidValidity() != null
                && metadata.getMessageUid() != null;
    }

    private EmailReference toReference(EmailMetadata metadata) {
        return new EmailReference(
                metadata.getMessageId(),
                metadata.getMailboxFolder(),
                metadata.getUidValidity() == null ? 0L : metadata.getUidValidity(),
                metadata.getMessageUid() == null ? 0L : metadata.getMessageUid());
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
