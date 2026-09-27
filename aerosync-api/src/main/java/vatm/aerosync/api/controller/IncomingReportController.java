package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.EmailReportSummaryResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.service.IncomingReportFilter;
import vatm.aerosync.api.service.IncomingReportService;
import vatm.aerosync.api.service.ReportFileType;
import vatm.aerosync.common.enums.EmailProcessingStatus;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/reports/incoming")
@Tag(name = "Incoming folder reports", description = "Search files ingested from the Incoming folder.")
public class IncomingReportController {
    private final IncomingReportService incomingReportService;
    public IncomingReportController(IncomingReportService incomingReportService) { this.incomingReportService = incomingReportService; }

    @GetMapping
    @Operation(summary = "List Incoming folder report records")
    public PagedResponse<EmailReportRowResponse> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) EmailProcessingStatus processingStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) ReportFileType fileType,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        IncomingReportFilter filter = new IncomingReportFilter(query, processingStatus, from, to, fileType);
        return incomingReportService.search(filter, page, size, snapshotId);
    }

    @GetMapping("/summary")
    public EmailReportSummaryResponse summarize(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) EmailProcessingStatus processingStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) ReportFileType fileType) {
        return incomingReportService.summarize(new IncomingReportFilter(query, processingStatus, from, to, fileType));
    }

    @GetMapping("/{id}")
    public EmailReportRowResponse get(@PathVariable Long id) { return incomingReportService.get(id); }
}
