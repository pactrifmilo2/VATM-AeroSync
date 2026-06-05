package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.dto.SyncJobDetailResponse;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.api.service.SyncJobService;
import vatm.aerosync.api.web.ApiExceptionHandler;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SyncJobController.class)
@Import(ApiExceptionHandler.class)
class SyncJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SyncJobService syncJobService;

    @Test
    void listJobs_returnsSummaries() throws Exception {
        SyncJobSummaryResponse summary = new SyncJobSummaryResponse(
                1L, "abc123", "valid-flights.csv", SyncStatus.FAILED, LocalDateTime.now(), LocalDateTime.now());
        when(syncJobService.listJobs(null)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void getJob_returnsDetail() throws Exception {
        SyncJobDetailResponse detail = new SyncJobDetailResponse(
                5L,
                "hash-5",
                SyncStatus.PENDING,
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of(),
                List.of(new RowValidationError(1, "callsign", "BR-CALLSIGN", "Invalid callsign", "!")),
                "BR-CALLSIGN: Row 1: Invalid callsign");
        when(syncJobService.getJob(5L)).thenReturn(detail);

        mockMvc.perform(get("/api/jobs/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.fileHash").value("hash-5"))
                .andExpect(jsonPath("$.rowErrors[0].code").value("BR-CALLSIGN"))
                .andExpect(jsonPath("$.latestLogMessage").value("BR-CALLSIGN: Row 1: Invalid callsign"));
    }

    @Test
    void retryJob_triggersRepublish() throws Exception {
        mockMvc.perform(post("/api/jobs/9/retry").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());

        verify(syncJobService).retryJob(9L);
    }
}
