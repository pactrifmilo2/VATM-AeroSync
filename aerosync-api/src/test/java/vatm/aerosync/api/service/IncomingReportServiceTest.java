package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncomingReportServiceTest {
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final FileRecordRepository files = mock(FileRecordRepository.class);
    private final PermitImportRepository permits = mock(PermitImportRepository.class);
    private final IncomingReportService service = new IncomingReportService(
            audits, files, permits, new VietnameseErrorMessageTranslator());

    @Test
    void filtersBeforeCountAndReusesSummarySnapshot() {
        AuditLog word = event(2L, "C:/incoming/PERMIT.DOCX", LocalDateTime.parse("2026-09-27T10:00:00"));
        AuditLog pdf = event(1L, "C:/incoming/report.pdf", LocalDateTime.parse("2026-09-27T09:00:00"));
        when(audits.findAll(any(Sort.class))).thenReturn(List.of(word, pdf));
        when(files.findBySyncJobIdIn(any())).thenReturn(List.of());
        when(permits.findBySyncJobIdIn(any())).thenReturn(List.of());
        IncomingReportFilter filter = new IncomingReportFilter(
                "permit", null, LocalDateTime.parse("2026-09-27T00:00:00"),
                LocalDateTime.parse("2026-09-27T23:59:59"), ReportFileType.WORD);

        var summary = service.summarize(filter);
        var page = service.search(filter, 0, 25, summary.snapshotId());

        assertThat(summary.totalFiles()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.snapshotId()).isEqualTo(summary.snapshotId());
        assertThat(page.content().getFirst().storedFileName()).isEqualTo("PERMIT.DOCX");
        assertThat(summary.totalFiles()).isEqualTo(summary.successFiles() + summary.errorFiles()
                + summary.pendingFiles() + summary.unknownFiles());
    }

    @Test
    void paginatesOneHundredTwentyFilteredWordRowsWithoutGaps() {
        java.util.ArrayList<AuditLog> events = new java.util.ArrayList<>();
        for (long id = 120; id >= 1; id--) {
            events.add(event(id, "C:/incoming/permit-" + id + ".DOCX",
                    LocalDateTime.parse("2026-09-27T10:00:00").minusSeconds(120 - id)));
        }
        when(audits.findAll(any(Sort.class))).thenReturn(events);
        IncomingReportFilter filter = new IncomingReportFilter(null, null, null, null, ReportFileType.WORD);
        var summary = service.summarize(filter);
        var first = service.search(filter, 0, 50, summary.snapshotId());
        var second = service.search(filter, 1, 50, summary.snapshotId());
        var third = service.search(filter, 2, 50, summary.snapshotId());
        assertThat(first.content()).hasSize(50); assertThat(second.content()).hasSize(50);
        assertThat(third.content()).hasSize(20);
        assertThat(first.totalElements()).isEqualTo(120); assertThat(second.totalElements()).isEqualTo(120);
        assertThat(java.util.stream.Stream.of(first, second, third).flatMap(page -> page.content().stream())
                .map(row -> row.id()).distinct()).hasSize(120);
    }

    @Test
    void detailReturnsOneIncomingRecord() {
        AuditLog event = event(9L, "C:/incoming/permit.docx", LocalDateTime.now());
        when(audits.findById(9L)).thenReturn(Optional.of(event));
        when(files.findBySyncJobIdIn(any())).thenReturn(List.of());
        when(permits.findBySyncJobIdIn(any())).thenReturn(List.of());
        assertThat(service.get(9L).sourceType()).isEqualTo("folder");
    }

    private AuditLog event(Long id, String path, LocalDateTime timestamp) {
        AuditLog event = mock(AuditLog.class);
        when(event.getId()).thenReturn(id);
        when(event.getAction()).thenReturn("INCOMING_DISCOVERED");
        when(event.getInputSummary()).thenReturn(path);
        when(event.getTimestamp()).thenReturn(timestamp);
        when(event.getResultStatus()).thenReturn(SyncStatus.PENDING);
        return event;
    }
}