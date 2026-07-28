package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.dto.EmailReportDetailResponse;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.EmailReportSummaryResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.service.EmailReportFilter;
import vatm.aerosync.api.service.EmailReportService;
import vatm.aerosync.api.web.ApiExceptionHandler;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmailReportController.class)
@Import(ApiExceptionHandler.class)
class EmailReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailReportService emailReportService;

    @Test
    void search_parsesFiltersAndReturnsPagedRows() throws Exception {
        LocalDateTime receivedAt = LocalDateTime.parse("2026-07-24T09:15:00");
        EmailReportFilter filter = new EmailReportFilter(
                LocalDateTime.parse("2026-07-01T00:00:00"),
                LocalDateTime.parse("2026-07-24T23:59:59"),
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                SyncStatus.FAILED,
                "operator@vatm.vn",
                "permit");
        EmailReportRowResponse row = new EmailReportRowResponse(
                102L,
                481L,
                "O/F 05199/S/CHK/2026",
                "mail-102",
                "operator@vatm.vn",
                "Flight permit update",
                receivedAt,
                1,
                0,
                "permit.docx",
                "operator_20260724_091600_email_permit.docx",
                EmailProcessingStatus.FAILED,
                EmailAcknowledgementStatus.MOVED_ERROR,
                true,
                receivedAt.plusMinutes(1),
                SyncStatus.FAILED);
        PagedResponse<EmailReportRowResponse> response = new PagedResponse<>(
                List.of(row), 1, 10, 11, 2, false, true);
        when(emailReportService.search(filter, 1, 10)).thenReturn(response);

        mockMvc.perform(get("/api/reports/emails")
                        .param("from", "2026-07-01T00:00:00")
                        .param("to", "2026-07-24T23:59:59")
                        .param("processingStatus", "FAILED")
                        .param("acknowledgementStatus", "MOVED_ERROR")
                        .param("jobStatus", "FAILED")
                        .param("sender", "operator@vatm.vn")
                        .param("query", "permit")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(102))
                .andExpect(jsonPath("$.content[0].permitNumber").value("O/F 05199/S/CHK/2026"))
                .andExpect(jsonPath("$.content[0].attachmentName").value("permit.docx"))
                .andExpect(jsonPath("$.content[0].storedFileName")
                        .value("operator_20260724_091600_email_permit.docx"))
                .andExpect(jsonPath("$.content[0].processingStatus").value("FAILED"))
                .andExpect(jsonPath("$.content[0].jobStatus").value("FAILED"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.hasPrevious").value(true));

        verify(emailReportService).search(filter, 1, 10);
    }

    @Test
    void get_returnsFullEmailDetail() throws Exception {
        EmailReportDetailResponse detail = new EmailReportDetailResponse(
                102L,
                null,
                null,
                "mail-102",
                "INBOX",
                4L,
                200L,
                "operator@vatm.vn",
                "No attachment",
                LocalDateTime.parse("2026-07-24T09:15:00"),
                0,
                0,
                null,
                null,
                "Email body",
                EmailProcessingStatus.NO_ATTACHMENT,
                EmailAcknowledgementStatus.PENDING,
                true,
                null,
                null,
                null);
        when(emailReportService.get(102L)).thenReturn(detail);

        mockMvc.perform(get("/api/reports/emails/102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(102))
                .andExpect(jsonPath("$.mailboxFolder").value("INBOX"))
                .andExpect(jsonPath("$.body").value("Email body"))
                .andExpect(jsonPath("$.processingStatus").value("NO_ATTACHMENT"));
    }

    @Test
    void summary_returnsStableStatusMaps() throws Exception {
        LocalDateTime from = LocalDateTime.parse("2026-07-01T00:00:00");
        LocalDateTime to = LocalDateTime.parse("2026-07-24T23:59:59");
        Map<String, Long> processing = new LinkedHashMap<>();
        processing.put("SAVED", 8L);
        processing.put("FAILED", 2L);
        Map<String, Long> acknowledgement = new LinkedHashMap<>();
        acknowledgement.put("MOVED_PROCESSED", 8L);
        acknowledgement.put("MOVED_ERROR", 2L);
        when(emailReportService.summarize(from, to))
                .thenReturn(new EmailReportSummaryResponse(from, to, 10, processing, acknowledgement));

        mockMvc.perform(get("/api/reports/emails/summary")
                        .param("from", "2026-07-01T00:00:00")
                        .param("to", "2026-07-24T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRecords").value(10))
                .andExpect(jsonPath("$.processingStatusCounts.SAVED").value(8))
                .andExpect(jsonPath("$.acknowledgementStatusCounts.MOVED_ERROR").value(2));
    }

    @Test
    void search_rejectsMalformedDate() throws Exception {
        mockMvc.perform(get("/api/reports/emails").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }
}
