package vatm.aerosync.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.ingest.config.EmailProperties;
import vatm.aerosync.ingest.email.EmailAttachment;
import vatm.aerosync.ingest.email.EmailClient;
import vatm.aerosync.ingest.email.EmailMessage;
import vatm.aerosync.ingest.support.Hashing;
import vatm.aerosync.ingest.support.PriorityDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class EmailIngestService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestService.class);
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".csv", ".xlsx", ".doc", ".docx", ".xml", ".json");

    private final EmailClient emailClient;
    private final EmailProperties emailProperties;
    private final DeduplicationService deduplicationService;
    private final IngestPublisher ingestPublisher;
    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final EmailFailureTracker emailFailureTracker;
    private final EmailAcknowledgementService emailAcknowledgementService;

    public EmailIngestService(EmailClient emailClient,
                              EmailProperties emailProperties,
                              DeduplicationService deduplicationService,
                              IngestPublisher ingestPublisher,
                              SyncJobRepository syncJobRepository,
                              FileRecordRepository fileRecordRepository,
                              EmailMetadataRepository emailMetadataRepository,
                              EmailFailureTracker emailFailureTracker,
                              EmailAcknowledgementService emailAcknowledgementService) {
        this.emailClient = emailClient;
        this.emailProperties = emailProperties;
        this.deduplicationService = deduplicationService;
        this.ingestPublisher = ingestPublisher;
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.emailFailureTracker = emailFailureTracker;
        this.emailAcknowledgementService = emailAcknowledgementService;
    }

    public int ingestUpTo(int limit) {
        try {
            List<EmailMessage> messages = emailClient.fetchMessages(
                    limit, emailAcknowledgementService::shouldDownload);
            emailFailureTracker.recordSuccess();
            int ingested = 0;
            for (EmailMessage message : messages) {
                if (ingested >= limit) {
                    break;
                }
                if (isBlockedByBlacklist(message.sender())) {
                    persistTerminalMessage(message, EmailProcessingStatus.BLOCKED);
                    emailAcknowledgementService.markIngestComplete(message.reference());
                    continue;
                }
                if (message.attachments().isEmpty()) {
                    log.warn("ALT-06: No attachment for message {}", message.messageId());
                    persistTerminalMessage(message, EmailProcessingStatus.NO_ATTACHMENT);
                    emailAcknowledgementService.markIngestComplete(message.reference());
                    continue;
                }
                boolean allAttachmentsVisited = true;
                for (int attachmentIndex = 0; attachmentIndex < message.attachments().size(); attachmentIndex++) {
                    if (ingested >= limit) {
                        allAttachmentsVisited = false;
                        break;
                    }
                    EmailAttachment attachment = message.attachments().get(attachmentIndex);
                    if (!isSupportedAttachment(attachment.fileName())) {
                        persistSkippedAttachment(message, attachment, attachmentIndex);
                        continue;
                    }
                    if (ingestAttachment(message, attachment, attachmentIndex)) {
                        ingested++;
                    }
                }
                if (allAttachmentsVisited) {
                    emailAcknowledgementService.markIngestComplete(message.reference());
                }
            }
            return ingested;
        } catch (RuntimeException ex) {
            emailFailureTracker.recordFailure();
            log.error("Email ingest failed", ex);
            return 0;
        }
    }

    private boolean isBlockedByBlacklist(String sender) {
        return emailProperties.getBlacklistSenders().stream()
                .anyMatch(blocked -> blocked.equalsIgnoreCase(sender));
    }

    private boolean ingestAttachment(EmailMessage message,
                                     EmailAttachment attachment,
                                     int attachmentIndex) {
        try {
            Optional<EmailMetadata> existingAttachment = emailMetadataRepository
                    .findByMailboxFolderAndUidValidityAndMessageUidAndAttachmentIndex(
                            message.mailboxFolder(),
                            message.uidValidity(),
                            message.messageUid(),
                            attachmentIndex);
            if (existingAttachment.isPresent()) {
                return false;
            }
            Path stagingDir = emailProperties.getStagingDir();
            Path messageStagingDir = stagingDir.resolve(messageStagingKey(message));
            Files.createDirectories(messageStagingDir);
            Path storedFile = messageStagingDir.resolve(
                    "%03d_%s".formatted(attachmentIndex, sanitizeFileName(attachment.fileName())));
            Files.write(storedFile, attachment.content());

            String hash = Hashing.sha256Hex(attachment.content());

            Optional<SyncJob> retryableJob = deduplicationService.findRetryableJob(hash);
            if (retryableJob.isPresent()) {
                republishExistingJob(
                        retryableJob.get(), message, attachment, attachmentIndex, storedFile, hash);
                return true;
            }
            if (deduplicationService.isDuplicate(hash)) {
                log.info("Skipping duplicate email attachment {} from {} (already completed successfully)",
                        attachment.fileName(), message.sender());
                SyncJob skippedJob = deduplicationService.createSkippedDuplicateJob(hash);
                emailMetadataRepository.save(toMetadata(
                        message,
                        attachment,
                        attachmentIndex,
                        skippedJob,
                        EmailProcessingStatus.SKIPPED));
                Files.deleteIfExists(storedFile);
                return false;
            }

            SyncJob job = new SyncJob();
            job.setFileHash(hash);
            job.setStatus(SyncStatus.PENDING);
            SyncJob saved = syncJobRepository.save(job);

            FileRecord record = new FileRecord();
            record.setSyncJob(saved);
            record.setSourceType(FileSourceType.EMAIL);
            record.setOriginalFileName(attachment.fileName());
            record.setStoredPath(storedFile.toAbsolutePath().normalize().toString());
            markDownloaded(record, attachment.content().length, hash);
            saved.addFileRecord(record);
            fileRecordRepository.save(record);

            emailMetadataRepository.save(toMetadata(
                    message,
                    attachment,
                    attachmentIndex,
                    saved,
                    EmailProcessingStatus.DOWNLOADED));

            deduplicationService.registerHash(hash);

            boolean priority = message.priority()
                    || PriorityDetector.isPriority(attachment.fileName(), message.subject());
            ingestPublisher.publish(new FileIngestedEvent(
                    saved.getId(),
                    record.getStoredPath(),
                    hash,
                    FileSourceType.EMAIL,
                    priority));
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store email attachment", e);
        }
    }

    private void republishExistingJob(SyncJob job,
                                      EmailMessage message,
                                      EmailAttachment attachment,
                                      int attachmentIndex,
                                      Path storedFile,
                                      String hash) {
        FileRecord record = fileRecordRepository.findBySyncJobId(job.getId()).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("No file records for job: " + job.getId()));
        record.setStoredPath(storedFile.toAbsolutePath().normalize().toString());
        markDownloaded(record, attachment.content().length, hash);
        fileRecordRepository.save(record);

        EmailMetadata metadata = emailMetadataRepository.findBySyncJobId(job.getId())
                .orElseGet(EmailMetadata::new);
        copyMessageMetadata(metadata, message, attachment, attachmentIndex);
        metadata.setSyncJob(job);
        metadata.setProcessingStatus(EmailProcessingStatus.DOWNLOADED);
        metadata.setIngestComplete(false);
        emailMetadataRepository.save(metadata);

        job.setStatus(SyncStatus.PENDING);
        syncJobRepository.save(job);

        boolean priority = message.priority()
                || PriorityDetector.isPriority(attachment.fileName(), message.subject());
        ingestPublisher.publish(new FileIngestedEvent(
                job.getId(),
                record.getStoredPath(),
                hash,
                FileSourceType.EMAIL,
                priority));
        log.info("Republishing email attachment {} from {} for retry (job {})",
                attachment.fileName(), message.sender(), job.getId());
    }

    private boolean isSupportedAttachment(String fileName) {
        if (fileName == null) {
            return false;
        }
        String normalized = fileName.toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }

    private void persistSkippedAttachment(EmailMessage message,
                                          EmailAttachment attachment,
                                          int attachmentIndex) {
        boolean alreadyRecorded = emailMetadataRepository
                .findByMailboxFolderAndUidValidityAndMessageUidAndAttachmentIndex(
                        message.mailboxFolder(),
                        message.uidValidity(),
                        message.messageUid(),
                        attachmentIndex)
                .isPresent();
        if (alreadyRecorded) {
            return;
        }
        emailMetadataRepository.save(toMetadata(
                message,
                attachment,
                attachmentIndex,
                null,
                EmailProcessingStatus.SKIPPED));
        log.info("Skipping unsupported email attachment {} from {}",
                attachment.fileName(), message.sender());
    }

    private void markDownloaded(FileRecord record, long fileSize, String checksum) {
        record.setProcessingStatus(FileProcessingStatus.DOWNLOADED);
        record.setDownloadedAt(LocalDateTime.now());
        record.setRowsSaved(null);
        record.setDatabaseSavedAt(null);
        record.setArchiveStatus(FileArchiveStatus.PENDING);
        record.setArchivedAt(null);
        record.setErrorMessage(null);
        record.setFileSize(fileSize);
        record.setChecksum(checksum);
    }

    private void persistTerminalMessage(EmailMessage message, EmailProcessingStatus status) {
        EmailMetadata metadata = toMetadata(message, null, 0, null, status);
        emailMetadataRepository.save(metadata);
    }

    private EmailMetadata toMetadata(EmailMessage message,
                                     EmailAttachment attachment,
                                     int attachmentIndex,
                                     SyncJob syncJob,
                                     EmailProcessingStatus status) {
        EmailMetadata metadata = new EmailMetadata();
        copyMessageMetadata(metadata, message, attachment, attachmentIndex);
        metadata.setSyncJob(syncJob);
        metadata.setProcessingStatus(status);
        return metadata;
    }

    private void copyMessageMetadata(EmailMetadata metadata,
                                     EmailMessage message,
                                     EmailAttachment attachment,
                                     int attachmentIndex) {
        metadata.setMessageId(message.messageId());
        metadata.setMailboxFolder(message.mailboxFolder());
        metadata.setUidValidity(message.uidValidity());
        metadata.setMessageUid(message.messageUid());
        metadata.setAttachmentIndex(attachmentIndex);
        metadata.setAttachmentName(attachment != null ? attachment.fileName() : null);
        metadata.setSender(message.sender());
        metadata.setSubject(message.subject());
        metadata.setReceivedAt(message.receivedAt());
        metadata.setAttachmentCount(message.attachments().size());
        metadata.setBody(message.body());
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String messageStagingKey(EmailMessage message) {
        if (message.messageUid() > 0) {
            return message.uidValidity() + "_" + message.messageUid();
        }
        return sanitizeFileName(message.messageId());
    }
}
