package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailReportServiceTest {

    private final EmailMetadataRepository repository = mock(EmailMetadataRepository.class);
    private final PermitImportRepository permitImportRepository = mock(PermitImportRepository.class);
    private final EmailReportService service = new EmailReportService(repository, permitImportRepository);

    @Test
    void search_mapsRowsWithoutEmailBodyAndUsesPaginationMetadata() {
        EmailMetadata metadata = metadata(
                "mail-1",
                "operator@vatm.vn",
                "Permit report",
                LocalDateTime.parse("2026-07-24T09:15:00"),
                EmailProcessingStatus.SAVED);
        metadata.setBody("large email body");
        when(repository.findAll(
                org.mockito.ArgumentMatchers.<Specification<EmailMetadata>>any(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(metadata)));

        var result = service.search(emptyFilter(), 0, 25);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().messageId()).isEqualTo("mail-1");
        assertThat(result.content().getFirst().processingStatus()).isEqualTo(EmailProcessingStatus.SAVED);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.page()).isZero();
    }

    @Test
    void get_returnsBodyAndThrowsForUnknownRecord() {
        EmailMetadata metadata = metadata(
                "mail-2",
                "operator@vatm.vn",
                "Permit detail",
                LocalDateTime.parse("2026-07-24T10:00:00"),
                EmailProcessingStatus.FAILED);
        metadata.setBody("Failure details");
        metadata.setAcknowledgementError("Could not move message");
        SyncJob syncJob = mock(SyncJob.class);
        when(syncJob.getId()).thenReturn(44L);
        when(syncJob.getStatus()).thenReturn(SyncStatus.FAILED);
        metadata.setSyncJob(syncJob);
        PermitImport permitImport = mock(PermitImport.class);
        when(permitImport.getNormalizedPermitId()).thenReturn("O/F 05199/S/CHK/2026");
        when(repository.findById(7L)).thenReturn(Optional.of(metadata));
        when(repository.findById(8L)).thenReturn(Optional.empty());
        when(permitImportRepository.findBySyncJobId(44L)).thenReturn(Optional.of(permitImport));

        assertThat(service.get(7L).body()).isEqualTo("Failure details");
        assertThat(service.get(7L).permitNumber()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(service.get(7L).acknowledgementError()).isEqualTo("Could not move message");
        assertThatThrownBy(() -> service.get(8L))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Email report record not found: 8");
    }

    @Test
    void search_validatesDateRangeAndPageBounds() {
        LocalDateTime later = LocalDateTime.parse("2026-07-24T10:00:00");
        LocalDateTime earlier = later.minusHours(1);
        EmailReportFilter invalidRange = new EmailReportFilter(
                later, earlier, null, null, null, null, null);

        assertThatThrownBy(() -> service.search(invalidRange, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'from' must be before or equal to 'to'");
        assertThatThrownBy(() -> service.search(emptyFilter(), -1, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'page' must be greater than or equal to 0");
        assertThatThrownBy(() -> service.search(emptyFilter(), 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'size' must be between 1 and 100");
    }

    @Test
    void summarizeIncludesZeroValuesForStatusesWithNoRecords() {
        EmailMetadataRepository.ProcessingStatusCount processingCount =
                mock(EmailMetadataRepository.ProcessingStatusCount.class);
        when(processingCount.getStatus()).thenReturn(EmailProcessingStatus.SAVED);
        when(processingCount.getTotal()).thenReturn(3L);
        EmailMetadataRepository.AcknowledgementStatusCount acknowledgementCount =
                mock(EmailMetadataRepository.AcknowledgementStatusCount.class);
        when(acknowledgementCount.getStatus()).thenReturn(EmailAcknowledgementStatus.MOVED_PROCESSED);
        when(acknowledgementCount.getTotal()).thenReturn(3L);
        when(repository.countByProcessingStatus(null, null)).thenReturn(List.of(processingCount));
        when(repository.countByAcknowledgementStatus(null, null)).thenReturn(List.of(acknowledgementCount));

        var result = service.summarize(null, null);

        assertThat(result.totalRecords()).isEqualTo(3);
        assertThat(result.processingStatusCounts())
                .containsEntry("SAVED", 3L)
                .containsEntry("FAILED", 0L);
        assertThat(result.acknowledgementStatusCounts())
                .containsEntry("MOVED_PROCESSED", 3L)
                .containsEntry("FAILED", 0L);
    }

    private EmailMetadata metadata(String messageId,
                                   String sender,
                                   String subject,
                                   LocalDateTime receivedAt,
                                   EmailProcessingStatus processingStatus) {
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId(messageId);
        metadata.setSender(sender);
        metadata.setSubject(subject);
        metadata.setReceivedAt(receivedAt);
        metadata.setAttachmentCount(1);
        metadata.setAttachmentIndex(0);
        metadata.setAttachmentName("permit.docx");
        metadata.setProcessingStatus(processingStatus);
        return metadata;
    }

    private EmailReportFilter emptyFilter() {
        return new EmailReportFilter(null, null, null, null, null, null, null);
    }
}
