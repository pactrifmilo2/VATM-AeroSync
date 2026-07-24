package vatm.aerosync.api.controller;

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
public class EmailReportController {

    private final EmailReportService emailReportService;

    public EmailReportController(EmailReportService emailReportService) {
        this.emailReportService = emailReportService;
    }

    @GetMapping
    public PagedResponse<EmailReportRowResponse> search(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) EmailProcessingStatus processingStatus,
            @RequestParam(required = false) EmailAcknowledgementStatus acknowledgementStatus,
            @RequestParam(required = false) SyncStatus jobStatus,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false, name = "query") String searchQuery,
            @RequestParam(defaultValue = "0") int page,
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
    public EmailReportSummaryResponse summarize(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return emailReportService.summarize(from, to);
    }

    @GetMapping("/{id}")
    public EmailReportDetailResponse get(@PathVariable Long id) {
        return emailReportService.get(id);
    }
}
