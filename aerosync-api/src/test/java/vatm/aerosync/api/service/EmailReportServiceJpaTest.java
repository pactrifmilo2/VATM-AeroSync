package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = EmailReportServiceJpaTest.TestConfiguration.class)
@Import({EmailReportService.class, VietnameseErrorMessageTranslator.class})
class EmailReportServiceJpaTest {

    @Autowired
    private EmailReportService service;

    @Autowired
    private EmailMetadataRepository emailMetadataRepository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private PermitImportRepository permitImportRepository;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Test
    void searchAppliesAllFiltersAndSortsNewestFirst() {
        LocalDateTime base = LocalDateTime.parse("2026-07-24T08:00:00");
        SyncJob failedJob = saveJob("a", SyncStatus.FAILED);
        SyncJob successfulJob = saveJob("b", SyncStatus.SUCCESS);
        EmailMetadata expected = saveEmail(
                "mail-expected",
                "operator@vatm.vn",
                "Urgent permit update",
                "permit_update.docx",
                base.plusHours(2),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                failedJob,
                101L);
        saveFileRecord(
                failedJob,
                "permit_update.docx",
                "C:\\vatm-storage\\error\\2026\\07\\24\\operator_20260724_100000_email_permit_update.docx");
        savePermitImport(failedJob, "O/F 05199/S/CHK/2026");
        saveEmail(
                "mail-wrong-job",
                "operator@vatm.vn",
                "Urgent permit update",
                "permit_update.docx",
                base.plusHours(1),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                successfulJob,
                102L);
        saveEmail(
                "mail-wrong-sender",
                "external@example.com",
                "Urgent permit update",
                "permit_update.docx",
                base.plusMinutes(30),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                failedJob,
                103L);

        EmailReportFilter filter = new EmailReportFilter(
                base,
                base.plusDays(1),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                SyncStatus.FAILED,
                "OPERATOR@VATM",
                "permit_update");

        var result = service.search(filter, 0, 25);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).extracting("id").containsExactly(expected.getId());
        assertThat(result.content().getFirst().permitNumber()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(result.content().getFirst().storedFileName())
                .isEqualTo("operator_20260724_100000_email_permit_update.docx");
        assertThat(result.content().getFirst().errorMessage())
                .isEqualTo("Không hỗ trợ định dạng giấy phép bay Word này; không có format YAML nào phù hợp.");
        assertThat(result.content().getFirst().jobStatus()).isEqualTo(SyncStatus.FAILED);
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsLiteralText() {
        LocalDateTime receivedAt = LocalDateTime.parse("2026-07-24T08:00:00");
        saveEmail(
                "literal-match",
                "operator@vatm.vn",
                "Permit 100%_verified",
                "permit.docx",
                receivedAt,
                EmailProcessingStatus.SAVED,
                EmailAcknowledgementStatus.MOVED_PROCESSED,
                null,
                201L);
        saveEmail(
                "wildcard-only",
                "operator@vatm.vn",
                "Permit 100Xverified",
                "permit.docx",
                receivedAt.minusMinutes(1),
                EmailProcessingStatus.SAVED,
                EmailAcknowledgementStatus.MOVED_PROCESSED,
                null,
                202L);

        EmailReportFilter filter = new EmailReportFilter(
                null, null, null, null, null, null, "100%_verified");

        var result = service.search(filter, 0, 25);

        assertThat(result.content()).extracting("messageId").containsExactly("literal-match");
    }

    @Test
    void summarizeRunsGroupedQueriesAndIncludesZeroStatuses() {
        LocalDateTime base = LocalDateTime.parse("2026-07-24T08:00:00");
        saveEmail(
                "mail-saved",
                "operator@vatm.vn",
                "Saved",
                "saved.docx",
                base,
                EmailProcessingStatus.SAVED,
                EmailAcknowledgementStatus.MOVED_PROCESSED,
                null,
                301L);
        saveEmail(
                "mail-failed",
                "operator@vatm.vn",
                "Failed",
                "failed.docx",
                base.plusHours(1),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                null,
                302L);
        saveEmail(
                "mail-outside-range",
                "operator@vatm.vn",
                "Old",
                "old.docx",
                base.minusDays(2),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                null,
                303L);

        var result = service.summarize(base.minusMinutes(1), base.plusDays(1));

        assertThat(result.totalRecords()).isEqualTo(2);
        assertThat(result.processingStatusCounts())
                .containsEntry("SAVED", 1L)
                .containsEntry("FAILED", 1L)
                .containsEntry("NO_ATTACHMENT", 0L);
        assertThat(result.acknowledgementStatusCounts())
                .containsEntry("MOVED_PROCESSED", 1L)
                .containsEntry("MOVED_ERROR", 1L)
                .containsEntry("PENDING", 0L);
    }

    private SyncJob saveJob(String hashCharacter, SyncStatus status) {
        SyncJob job = new SyncJob();
        job.setFileHash(hashCharacter.repeat(64));
        job.setStatus(status);
        return syncJobRepository.saveAndFlush(job);
    }

    private EmailMetadata saveEmail(String messageId,
                                    String sender,
                                    String subject,
                                    String attachmentName,
                                    LocalDateTime receivedAt,
                                    EmailProcessingStatus processingStatus,
                                    EmailAcknowledgementStatus acknowledgementStatus,
                                    SyncJob syncJob,
                                    long messageUid) {
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId(messageId);
        metadata.setMailboxFolder("INBOX");
        metadata.setUidValidity(1L);
        metadata.setMessageUid(messageUid);
        metadata.setAttachmentIndex(0);
        metadata.setAttachmentName(attachmentName);
        metadata.setSender(sender);
        metadata.setSubject(subject);
        metadata.setReceivedAt(receivedAt);
        metadata.setAttachmentCount(1);
        metadata.setProcessingStatus(processingStatus);
        metadata.setAcknowledgementStatus(acknowledgementStatus);
        metadata.setSyncJob(syncJob);
        return emailMetadataRepository.saveAndFlush(metadata);
    }

    private void savePermitImport(SyncJob syncJob, String normalizedPermitId) {
        PermitImport permitImport = new PermitImport();
        permitImport.setSyncJob(syncJob);
        permitImport.setNormalizedPermitId(normalizedPermitId);
        permitImport.setSemanticHash("c".repeat(64));
        permitImport.setSourceFileHash(syncJob.getFileHash());
        permitImportRepository.saveAndFlush(permitImport);
    }

    private void saveFileRecord(SyncJob syncJob, String originalFileName, String storedPath) {
        FileRecord record = new FileRecord();
        record.setSyncJob(syncJob);
        record.setSourceType(FileSourceType.EMAIL);
        record.setOriginalFileName(originalFileName);
        record.setStoredPath(storedPath);
        record.setErrorMessage("Unsupported Word permit format; no format profile matched");
        fileRecordRepository.saveAndFlush(record);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("vatm.aerosync.common.entity")
    @EnableJpaRepositories("vatm.aerosync.common.repository")
    static class TestConfiguration {
    }
}
