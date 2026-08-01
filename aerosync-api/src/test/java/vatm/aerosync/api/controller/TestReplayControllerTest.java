package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.dto.TestReplayResponse;
import vatm.aerosync.api.service.TestReplayService;
import vatm.aerosync.api.web.ApiExceptionHandler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TestReplayController.class)
@Import(ApiExceptionHandler.class)
class TestReplayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestReplayService testReplayService;

    @Test
    void replay_requiresExplicitPermitConfirmationAndReturnsDeletionCounts() throws Exception {
        when(testReplayService.replay(9L, "LD-06/A/S/2026"))
                .thenReturn(new TestReplayResponse(9L, "LD-06/A/S/2026", 1, 3, true));

        mockMvc.perform(post("/api/testing/jobs/9/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmPermitId":"LD-06/A/S/2026"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(9))
                .andExpect(jsonPath("$.normalizedPermitId").value("LD-06/A/S/2026"))
                .andExpect(jsonPath("$.deletedTargetMasterRows").value(1))
                .andExpect(jsonPath("$.deletedTargetDetailRows").value(3))
                .andExpect(jsonPath("$.replayQueued").value(true));

        verify(testReplayService).replay(9L, "LD-06/A/S/2026");
    }

    @Test
    void replay_rejectsBlankConfirmation() throws Exception {
        mockMvc.perform(post("/api/testing/jobs/9/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmPermitId":" "}
                                """))
                .andExpect(status().isBadRequest());
    }
}
