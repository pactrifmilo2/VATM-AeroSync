package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import vatm.aerosync.api.config.EmailResendProperties;
import vatm.aerosync.api.dto.EmailResendRequest;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailResendServiceTest {

    @TempDir
    Path tempDir;

    private final EmailMetadataRepository metadataRepository = mock(EmailMetadataRepository.class);
    private final FileRecordRepository fileRecordRepository = mock(FileRecordRepository.class);
    private final OutboundEmailSender emailSender = mock(OutboundEmailSender.class);
    private final EmailResendProperties properties = new EmailResendProperties();
    private final EmailResendService service = new EmailResendService(
            properties, metadataRepository, fileRecordRepository, emailSender);

    @Test
    void resend_sendsOnlyAttachmentsMatchingSelectedStatuses() throws Exception {
        properties.setRecipient("ingest@vatm.vn");
        SyncJob failedJob = mock(SyncJob.class);
        SyncJob savedJob = mock(SyncJob.class);
        when(failedJob.getId()).thenReturn(11L);
        when(savedJob.getId()).thenReturn(12L);

        EmailMetadata failed = metadata(
                failedJob, EmailProcessingStatus.FAILED, "failed.docx", "Original subject", "Original body");
        EmailMetadata saved = metadata(
                savedJob, EmailProcessingStatus.SAVED, "saved.docx", "Original subject", "Original body");
        when(metadataRepository.findByMessageIdOrderByAttachmentIndexAsc("mail-1"))
                .thenReturn(List.of(failed, saved));

        Path archived = Files.writeString(tempDir.resolve("archived-failed.docx"), "sample");
        FileRecord record = mock(FileRecord.class);
        when(record.getStoredPath()).thenReturn(archived.toString());
        when(record.getOriginalFileName()).thenReturn("fallback.docx");
        when(record.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(record.getId()).thenReturn(101L);
        when(fileRecordRepository.findBySyncJobId(11L)).thenReturn(List.of(record));
        when(emailSender.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OutboundEmailSender.SendResult("gmail-message-1", "ingest@vatm.vn"));

        var response = service.resend(new EmailResendRequest(
                "mail-1", Set.of(EmailProcessingStatus.FAILED)));

        assertThat(response.attachmentsSent()).isEqualTo(1);
        assertThat(response.attachmentsSkipped()).isZero();
        assertThat(response.sentMessageId()).isEqualTo("gmail-message-1");
        ArgumentCaptor<OutboundEmailSender.OutboundEmail> captor =
                ArgumentCaptor.forClass(OutboundEmailSender.OutboundEmail.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Original subject");
        assertThat(captor.getValue().body()).isEqualTo("Original body");
        assertThat(captor.getValue().attachments())
                .extracting(OutboundEmailSender.Attachment::fileName)
                .containsExactly("failed.docx");
        assertThat(captor.getValue().headers().get("X-AeroSync-Original-Message-ID"))
                .isEqualTo("mail-1");
    }

    private EmailMetadata metadata(SyncJob job,
                                   EmailProcessingStatus status,
                                   String attachmentName,
                                   String subject,
                                   String body) {
        EmailMetadata metadata = mock(EmailMetadata.class);
        when(metadata.getSyncJob()).thenReturn(job);
        when(metadata.getProcessingStatus()).thenReturn(status);
        when(metadata.getAttachmentName()).thenReturn(attachmentName);
        when(metadata.getSubject()).thenReturn(subject);
        when(metadata.getBody()).thenReturn(body);
        when(metadata.getSender()).thenReturn("original@gmail.com");
        return metadata;
    }
}
