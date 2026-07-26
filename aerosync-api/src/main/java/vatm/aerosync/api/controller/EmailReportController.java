package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.EmailReportDetailResponse;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.EmailReportSummaryResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.service.EmailReportFilter;
import vatm.aerosync.api.service.EmailReportService;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/reports/emails")
@Tag(name = "Email reports", description = "Search and inspect email-ingestion report records.")
public class EmailReportController {

    private final EmailReportService emailReportService;

    public EmailReportController(EmailReportService emailReportService) {
        this.emailReportService = emailReportService;
    }

    @GetMapping
    @Operation(
            summary = "List email report records",
            description = "Returns one paginated report row per email metadata record, normally one per attachment.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter, date range, or pagination")
    })
    public PagedResponse<EmailReportRowResponse> search(
            @Parameter(description = "Inclusive received-at lower bound")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Inclusive received-at upper bound")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "Email processing lifecycle status")
            @RequestParam(required = false) EmailProcessingStatus processingStatus,
            @Parameter(description = "Mailbox acknowledgement status")
            @RequestParam(required = false) EmailAcknowledgementStatus acknowledgementStatus,
            @Parameter(description = "Associated synchronization job status")
            @RequestParam(required = false) SyncStatus jobStatus,
            @Parameter(description = "Case-insensitive partial sender match")
            @RequestParam(required = false) String sender,
            @Parameter(description = "Search message ID, sender, subject, and attachment name")
            @RequestParam(required = false, name = "query") String searchQuery,
            @Parameter(description = "Zero-based page number", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size from 1 to 100", example = "25")
            @RequestParam(defaultValue = "25") int size) {
        EmailReportFilter filter = new EmailReportFilter(
                from,
                to,
                processingStatus,
                acknowledgementStatus,
                jobStatus,
                sender,
                searchQuery);
        return emailReportService.search(filter, page, size);
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Summarize email report records",
            description = "Returns processing and acknowledgement counts for an optional received-at range.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report summary returned"),
            @ApiResponse(responseCode = "400", description = "Invalid date range")
    })
    public EmailReportSummaryResponse summarize(
            @Parameter(description = "Inclusive received-at lower bound")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Inclusive received-at upper bound")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return emailReportService.summarize(from, to);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an email report record", description = "Returns full metadata and email body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report record returned"),
            @ApiResponse(responseCode = "404", description = "Report record not found")
    })
    public EmailReportDetailResponse get(
            @Parameter(description = "Email metadata record ID", required = true)
            @PathVariable Long id) {
        return emailReportService.get(id);
    }
}
