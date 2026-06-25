package vatm.aerosync.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class EmailIngestService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestService.class);

    private final EmailClient emailClient;
    private final EmailProperties emailProperties;
    private final DeduplicationService deduplicationService;
    private final IngestPublisher ingestPublisher;
    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final EmailFailureTracker emailFailureTracker;

    public EmailIngestService(EmailClient emailClient,
                              EmailProperties emailProperties,
                              DeduplicationService deduplicationService,
                              IngestPublisher ingestPublisher,
                              SyncJobRepository syncJobRepository,
                              FileRecordRepository fileRecordRepository,
                              EmailMetadataRepository emailMetadataRepository,
                              EmailFailureTracker emailFailureTracker) {
        this.emailClient = emailClient;
        this.emailProperties = emailProperties;
        this.deduplicationService = deduplicationService;
        this.ingestPublisher = ingestPublisher;
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.emailFailureTracker = emailFailureTracker;
    }

    public int ingestUpTo(int limit) {
        try {
            List<EmailMessage> messages = emailClient.fetchMessages(limit);
            emailFailureTracker.recordSuccess();
            int ingested = 0;
            for (EmailMessage message : messages) {
                if (ingested >= limit) {
                    break;
                }
                if (isBlockedByBlacklist(message.sender())) {
                    continue;
                }
                if (message.attachments().isEmpty()) {
                    log.warn("ALT-06: No attachment for message {}", message.messageId());
                    continue;
                }
                for (EmailAttachment attachment : message.attachments()) {
                    if (ingested >= limit) {
                        break;
                    }
                    if (ingestAttachment(message, attachment)) {
                        ingested++;
                    }
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

    private boolean ingestAttachment(EmailMessage message, EmailAttachment attachment) {
        try {
            Path stagingDir = emailProperties.getStagingDir();
            Files.createDirectories(stagingDir);
            Path storedFile = stagingDir.resolve(sanitizeFileName(attachment.fileName()));
            Files.write(storedFile, attachment.content());

            String hash = Hashing.sha256Hex(attachment.content());

            Optional<SyncJob> retryableJob = deduplicationService.findRetryableJob(hash);
            if (retryableJob.isPresent()) {
                republishExistingJob(retryableJob.get(), message, attachment, storedFile, hash);
                return true;
            }
            if (deduplicationService.isDuplicate(hash)) {
                log.info("Skipping duplicate email attachment {} from {} (already completed successfully)",
                        attachment.fileName(), message.sender());
                deduplicationService.createSkippedDuplicateJob(hash);
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
            saved.addFileRecord(record);
            fileRecordRepository.save(record);

            EmailMetadata metadata = new EmailMetadata();
            metadata.setSyncJob(saved);
            metadata.setMessageId(message.messageId());
            metadata.setSender(message.sender());
            metadata.setSubject(message.subject());
            metadata.setReceivedAt(message.receivedAt());
            metadata.setAttachmentCount(message.attachments().size());
            emailMetadataRepository.save(metadata);

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
                                      Path storedFile,
                                      String hash) {
        FileRecord record = fileRecordRepository.findBySyncJobId(job.getId()).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("No file records for job: " + job.getId()));
        record.setStoredPath(storedFile.toAbsolutePath().normalize().toString());
        fileRecordRepository.save(record);

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

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
