package vatm.aerosync.ingest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import vatm.aerosync.ingest.email.EmailClient;
import vatm.aerosync.ingest.email.EmailMessage;
import vatm.aerosync.ingest.email.EmailAttachment;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailIngestServiceTest {

    @Mock
    private EmailClient emailClient;

    @Mock
    private EmailProperties emailProperties;

    @Mock
    private DeduplicationService deduplicationService;

    @Mock
    private IngestPublisher ingestPublisher;

    @Mock
    private SyncJobRepository syncJobRepository;

    @Mock
    private FileRecordRepository fileRecordRepository;

    @Mock
    private EmailMetadataRepository emailMetadataRepository;

    @Mock
    private EmailFailureTracker emailFailureTracker;

    private EmailIngestService emailIngestService;

    @BeforeEach
    void setUp() {
        when(emailProperties.getWhitelistSenders()).thenReturn(List.of("ops@vatm.local"));
        when(emailProperties.getStagingDir()).thenReturn(Path.of(System.getProperty("java.io.tmpdir"), "aerosync-email-test"));
        emailIngestService = new EmailIngestService(
                emailClient,
                emailProperties,
                deduplicationService,
                ingestPublisher,
                syncJobRepository,
                fileRecordRepository,
                emailMetadataRepository,
                emailFailureTracker);
    }

    @Test
    void ingestUpTo_skipsNonWhitelistedSender() {
        EmailMessage message = new EmailMessage(
                "msg-1", "unknown@evil.com", "Data", LocalDateTime.now(),
                List.of(new EmailAttachment("flight.csv", "x".getBytes())), false);
        when(emailClient.fetchMessages(10)).thenReturn(List.of(message));

        int ingested = emailIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(ingestPublisher, never()).publish(any());
        verify(emailFailureTracker).recordSuccess();
    }

    @Test
    void ingestUpTo_skipsMessageWithNoAttachment_alt06() {
        EmailMessage message = new EmailMessage(
                "msg-2", "ops@vatm.local", "No files", LocalDateTime.now(), List.of(), false);
        when(emailClient.fetchMessages(10)).thenReturn(List.of(message));

        int ingested = emailIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(ingestPublisher, never()).publish(any());
        verify(emailMetadataRepository, never()).save(any());
    }

    @Test
    void ingestUpTo_persistsMetadataAndPublishesForWhitelistedAttachment() {
        byte[] content = "callsign,from,to".getBytes();
        EmailMessage message = new EmailMessage(
                "msg-3", "ops@vatm.local", "URGENT flight data", LocalDateTime.now(),
                List.of(new EmailAttachment("flight.csv", content)), true);
        when(emailClient.fetchMessages(10)).thenReturn(List.of(message));
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));

        int ingested = emailIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(1);
        verify(emailFailureTracker).recordSuccess();

        ArgumentCaptor<EmailMetadata> metadataCaptor = ArgumentCaptor.forClass(EmailMetadata.class);
        verify(emailMetadataRepository).save(metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().getMessageId()).isEqualTo("msg-3");
        assertThat(metadataCaptor.getValue().getSender()).isEqualTo("ops@vatm.local");
        assertThat(metadataCaptor.getValue().getAttachmentCount()).isEqualTo(1);

        verify(ingestPublisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.getSourceType() == FileSourceType.EMAIL && event.isPriority()));
    }

    @Test
    void ingestUpTo_republishesFailedAttachmentForRetry() {
        byte[] content = "retry-me".getBytes();
        EmailMessage message = new EmailMessage(
                "msg-retry", "ops@vatm.local", "Retry flight data", LocalDateTime.now(),
                List.of(new EmailAttachment("flight.csv", content)), false);
        when(emailClient.fetchMessages(10)).thenReturn(List.of(message));

        SyncJob failedJob = org.mockito.Mockito.mock(SyncJob.class);
        when(failedJob.getId()).thenReturn(42L);
        when(failedJob.getFileHash()).thenReturn("failed-hash");
        when(failedJob.getStatus()).thenReturn(SyncStatus.FAILED);

        FileRecord record = new FileRecord();
        record.setOriginalFileName("flight.csv");
        record.setStoredPath("/tmp/old-flight.csv");

        when(deduplicationService.findRetryableJob(anyString())).thenReturn(Optional.of(failedJob));
        when(fileRecordRepository.findBySyncJobId(42L)).thenReturn(List.of(record));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileRecordRepository.save(any(FileRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        int ingested = emailIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.getSyncJobId() == 42L && event.getSourceType() == FileSourceType.EMAIL));
        verify(deduplicationService, never()).createSkippedDuplicateJob(anyString());
    }

    @Test
    void ingestUpTo_logsAndSkipsDuplicateAttachmentWithoutFailingCycle() {
        byte[] content = "already-processed".getBytes();
        EmailMessage message = new EmailMessage(
                "msg-dup", "ops@vatm.local", "Duplicate flight data", LocalDateTime.now(),
                List.of(new EmailAttachment("flight.csv", content)), false);
        when(emailClient.fetchMessages(10)).thenReturn(List.of(message));
        when(deduplicationService.findRetryableJob(anyString())).thenReturn(Optional.empty());
        when(deduplicationService.isDuplicate(anyString())).thenReturn(true);
        SyncJob existing = new SyncJob();
        existing.setFileHash("existing-hash");
        when(deduplicationService.createSkippedDuplicateJob(anyString())).thenReturn(existing);

        int ingested = emailIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(emailFailureTracker).recordSuccess();
        verify(ingestPublisher, never()).publish(any());
        verify(deduplicationService).createSkippedDuplicateJob(anyString());
    }

    @Test
    void ingestUpTo_recordsFailureWhenEmailClientThrows() {
        when(emailClient.fetchMessages(10)).thenThrow(new RuntimeException("IMAP down"));

        int ingested = emailIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(emailFailureTracker).recordFailure();
        verify(ingestPublisher, never()).publish(any());
    }
}
