package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.dto.AuditLogResponse;
import vatm.aerosync.api.service.AuditLogQueryService;
import vatm.aerosync.api.web.ApiExceptionHandler;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
@Import(ApiExceptionHandler.class)
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

    @Test
    void listAuditLogs_appliesDateStatusAndSourceFilters() throws Exception {
        AuditLogResponse row = new AuditLogResponse(
                1L, 10L, "FILE_PROCESSED", SyncStatus.SUCCESS,
                LocalDateTime.parse("2026-06-04T10:00:00"), 120L, FileSourceType.EMAIL);
        when(auditLogQueryService.search(
                eq(LocalDateTime.parse("2026-06-04T00:00:00")),
                eq(LocalDateTime.parse("2026-06-04T23:59:59")),
                eq(SyncStatus.SUCCESS),
                eq(FileSourceType.EMAIL)))
                .thenReturn(List.of(row));

        mockMvc.perform(get("/api/audit-logs")
                        .param("from", "2026-06-04T00:00:00")
                        .param("to", "2026-06-04T23:59:59")
                        .param("status", "SUCCESS")
                        .param("source", "EMAIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("FILE_PROCESSED"))
                .andExpect(jsonPath("$[0].sourceType").value("EMAIL"));
    }

    @Test
    void listAuditLogs_withoutFilters_delegatesWithNulls() throws Exception {
        when(auditLogQueryService.search(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
