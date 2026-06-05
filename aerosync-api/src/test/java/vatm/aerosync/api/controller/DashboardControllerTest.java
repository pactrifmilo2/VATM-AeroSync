package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.dto.DashboardStatsResponse;
import vatm.aerosync.api.service.DashboardService;
import vatm.aerosync.api.web.ApiExceptionHandler;
import vatm.aerosync.common.enums.SyncStatus;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import(ApiExceptionHandler.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void getStats_returnsAggregatedCounts() throws Exception {
        DashboardStatsResponse stats = new DashboardStatsResponse(
                10L,
                Map.of(
                        SyncStatus.SUCCESS.name(), 6L,
                        SyncStatus.FAILED.name(), 2L,
                        SyncStatus.PENDING.name(), 2L),
                4L,
                1L,
                3L);
        when(dashboardService.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(10))
                .andExpect(jsonPath("$.statusCounts.SUCCESS").value(6))
                .andExpect(jsonPath("$.jobsLast24Hours").value(4))
                .andExpect(jsonPath("$.failedLast24Hours").value(1))
                .andExpect(jsonPath("$.activeAlerts").value(3));
    }
}
