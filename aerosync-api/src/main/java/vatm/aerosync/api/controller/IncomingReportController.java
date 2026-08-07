package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.service.IncomingReportService;

@RestController
@RequestMapping("/api/reports/incoming")
@Tag(name = "Incoming folder reports", description = "Search files ingested from the Incoming folder.")
public class IncomingReportController {

    private final IncomingReportService incomingReportService;

    public IncomingReportController(IncomingReportService incomingReportService) {
        this.incomingReportService = incomingReportService;
    }

    @GetMapping
    @Operation(summary = "List Incoming folder report records",
            description = "Returns the same row structure as /api/reports/emails so EmailReports.aspx can reuse its table rendering.")
    public PagedResponse<EmailReportRowResponse> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return incomingReportService.search(page, size);
    }
}
