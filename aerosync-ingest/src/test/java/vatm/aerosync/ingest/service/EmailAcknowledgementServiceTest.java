package vatm.aerosync.ingest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.common.dto.SyncResultEvent;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.enums.AlertLevel;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailAcknowledgementServiceTest {

    @Mock
    private EmailMetadataRepository emailMetadataRepository;

    @Mock
    private EmailClient emailClient;

    private final EmailProperties emailProperties = new EmailProperties();

    private EmailAcknowledgementService service;

    @BeforeEach
    void setUp() {
        emailProperties.setAcknowledgementEnabled(true);
        service = new EmailAcknowledgementService(emailMetadataRepository, emailClient, emailProperties);
    }

    @Test
    void shouldDownload_skipsCompletedMailboxUidBeforeAttachmentDownload() {
        EmailEnvelope envelope = new EmailEnvelope(
                "message-1", "ops@vatm.local", "Flight", LocalDateTime.now(),
                "INBOX", 100L, 200L, false);
        when(emailMetadataRepository
                .existsByMailboxFolderAndUidValidityAndMessageUidAndIngestCompleteTrue(
                        "INBOX", 100L, 200L))
                .thenReturn(true);

        assertThat(service.shouldDownload(envelope)).isFalse();
    }

    @Test
    void shouldDownload_skipsCompletedMessageIdAfterMailboxRestoreChangesUid() {
        EmailEnvelope envelope = new EmailEnvelope(
                "message-restored", "ops@vatm.local", "Flight", LocalDateTime.now(),
                "INBOX", 100L, 999L, false);
        when(emailMetadataRepository
                .existsByMailboxFolderAndUidValidityAndMessageUidAndIngestCompleteTrue(
                        "INBOX", 100L, 999L))
                .thenReturn(false);
        when(emailMetadataRepository.existsByMessageIdAndIngestCompleteTrue("message-restored"))
                .thenReturn(true);

        assertThat(service.shouldDownload(envelope)).isFalse();
    }

    @Test
    void acknowledgeIfComplete_doesNotMoveMailWhenAcknowledgementIsDisabled() {
        emailProperties.setAcknowledgementEnabled(false);
        EmailReference reference = new EmailReference("message-1", "INBOX", 100L, 200L);

        service.acknowledgeIfComplete(reference);

        verify(emailClient, never()).acknowledge(reference, EmailDisposition.PROCESSED);
        verify(emailClient, never()).acknowledge(reference, EmailDisposition.ERROR);
    }

    @Test
    void acknowledgeIfComplete_movesMessageToProcessedOnlyAfterEveryAttachmentIsSaved() {
        EmailMetadata first = metadata(0, 2, EmailProcessingStatus.SAVED);
        EmailMetadata second = metadata(1, 2, EmailProcessingStatus.SAVED);
        EmailReference reference = new EmailReference("message-1", "INBOX", 100L, 200L);
        when(emailMetadataRepository.findByMailboxFolderAndUidValidityAndMessageUid(
                "INBOX", 100L, 200L)).thenReturn(List.of(first, second));

        service.acknowledgeIfComplete(reference);

        verify(emailClient).acknowledge(reference, EmailDisposition.PROCESSED);
        assertThat(first.getAcknowledgementStatus()).isEqualTo(EmailAcknowledgementStatus.MOVED_PROCESSED);
        assertThat(second.getAcknowledgedAt()).isNotNull();
        verify(emailMetadataRepository).saveAll(List.of(first, second));
    }

    @Test
    void onSyncResult_movesFailedMessageToErrorMailbox() {
        EmailMetadata metadata = metadata(0, 1, EmailProcessingStatus.PROCESSING);
        when(emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(42L)).thenReturn(Optional.of(metadata));
        when(emailMetadataRepository.findByMailboxFolderAndUidValidityAndMessageUid(
                "INBOX", 100L, 200L)).thenReturn(List.of(metadata));
        SyncResultEvent event = new SyncResultEvent(
                42L, SyncStatus.FAILED, AlertLevel.WARNING, "invalid", LocalDateTime.now());

        service.onSyncResult(event);

        verify(emailClient).acknowledge(
                new EmailReference("message-1", "INBOX", 100L, 200L),
                EmailDisposition.ERROR);
        assertThat(metadata.getProcessingStatus()).isEqualTo(EmailProcessingStatus.FAILED);
        assertThat(metadata.getAcknowledgementStatus()).isEqualTo(EmailAcknowledgementStatus.MOVED_ERROR);
    }

    @Test
    void acknowledgeIfComplete_waitsForRemainingAttachment() {
        EmailMetadata first = metadata(0, 2, EmailProcessingStatus.SAVED);
        EmailReference reference = new EmailReference("message-1", "INBOX", 100L, 200L);
        when(emailMetadataRepository.findByMailboxFolderAndUidValidityAndMessageUid(
                "INBOX", 100L, 200L)).thenReturn(List.of(first));

        service.acknowledgeIfComplete(reference);

        verify(emailClient, never()).acknowledge(reference, EmailDisposition.PROCESSED);
    }

    private EmailMetadata metadata(int index, int count, EmailProcessingStatus status) {
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId("message-1");
        metadata.setMailboxFolder("INBOX");
        metadata.setUidValidity(100L);
        metadata.setMessageUid(200L);
        metadata.setAttachmentIndex(index);
        metadata.setAttachmentCount(count);
        metadata.setProcessingStatus(status);
        metadata.setIngestComplete(true);
        return metadata;
    }
}
