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

    @Test
    void resend_attachmentIdsSelectExactWordRecordAndAuthorizeOnlyItsJob() throws Exception {
        properties.setRecipient("ingest@vatm.vn");
        EmailResendAuthorizationService authorization = mock(EmailResendAuthorizationService.class);
        EmailResendService selectiveService = new EmailResendService(
                properties, metadataRepository, fileRecordRepository, emailSender, authorization);
        SyncJob firstJob = mock(SyncJob.class);
        SyncJob secondJob = mock(SyncJob.class);
        when(firstJob.getId()).thenReturn(21L);
        when(secondJob.getId()).thenReturn(22L);
        EmailMetadata first = metadata(
                firstJob, EmailProcessingStatus.SAVED, "first.docx", "Subject", "Body");
        EmailMetadata second = metadata(
                secondJob, EmailProcessingStatus.SAVED, "second.docx", "Subject", "Body");
        when(first.getId()).thenReturn(201L);
        when(second.getId()).thenReturn(202L);
        when(metadataRepository.findByMessageIdOrderByAttachmentIndexAsc("mail-2"))
                .thenReturn(List.of(first, second));
        Path firstFile = Files.writeString(tempDir.resolve("first.docx"), "first");
        Path secondFile = Files.writeString(tempDir.resolve("second.docx"), "second");
        FileRecord firstRecord = record(firstFile, 301L);
        FileRecord secondRecord = record(secondFile, 302L);
        when(fileRecordRepository.findBySyncJobId(21L)).thenReturn(List.of(firstRecord));
        when(fileRecordRepository.findBySyncJobId(22L)).thenReturn(List.of(secondRecord));
        when(emailSender.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OutboundEmailSender.SendResult("gmail-message-2", "ingest@vatm.vn"));

        var response = selectiveService.resend(new EmailResendRequest(
                "mail-2", Set.of(EmailProcessingStatus.SAVED), Set.of(202L)));

        assertThat(response.attachmentsSent()).isOne();
        ArgumentCaptor<OutboundEmailSender.OutboundEmail> emailCaptor =
                ArgumentCaptor.forClass(OutboundEmailSender.OutboundEmail.class);
        verify(emailSender).send(emailCaptor.capture());
        assertThat(emailCaptor.getValue().attachments())
                .extracting(OutboundEmailSender.Attachment::fileName)
                .containsExactly("second.docx");
        verify(authorization).authorize(org.mockito.ArgumentMatchers.argThat(jobs ->
                jobs.size() == 1 && jobs.iterator().next() == secondJob));
    }

    private FileRecord record(Path path, long id) {
        FileRecord record = mock(FileRecord.class);
        when(record.getStoredPath()).thenReturn(path.toString());
        when(record.getOriginalFileName()).thenReturn(path.getFileName().toString());
        when(record.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(record.getId()).thenReturn(id);
        return record;
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
