package vatm.aerosync.api.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.AuditLogResponse;
import vatm.aerosync.api.service.AuditLogQueryService;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping
    public List<AuditLogResponse> listAuditLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) SyncStatus status,
            @RequestParam(required = false) FileSourceType source) {
        return auditLogQueryService.search(from, to, status, source);
    }
}
