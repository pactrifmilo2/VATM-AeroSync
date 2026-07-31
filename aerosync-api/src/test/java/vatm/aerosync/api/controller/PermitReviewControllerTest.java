package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.AerosyncApiApplication;
import vatm.aerosync.api.config.ApiDataConfig;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.service.PermitReviewService;
import vatm.aerosync.api.service.PermitTrainingCandidateService;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {AerosyncApiApplication.class, ApiDataConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermitReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PermitReviewService permitReviewService;

    @MockitoBean
    private PermitTrainingCandidateService trainingCandidateService;

    @Test
    void anonymousUserCannotReadReviewQueue() throws Exception {
        mockMvc.perform(get("/api/permit-reviews"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorCanReadReviewQueue() throws Exception {
        when(permitReviewService.list(null, 0, 25))
                .thenReturn(new PagedResponse<>(List.of(), 0, 25, 0, 0, false, false));

        mockMvc.perform(get("/api/permit-reviews")
                        .with(user("operator.one").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void operatorCannotPublish() throws Exception {
        mockMvc.perform(post("/api/permit-reviews/4/publish")
                        .with(user("operator.one").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRequestPublication() throws Exception {
        mockMvc.perform(post("/api/permit-reviews/4/publish")
                        .with(user("admin.one").roles("ADMIN")))
                .andExpect(status().isAccepted());

        verify(permitReviewService).requestPublish(4L, "admin.one");
    }

    @Test
    void operatorCannotApproveTrainingCandidate() throws Exception {
        mockMvc.perform(post("/api/permit-training-candidates/9/approve")
                        .with(user("operator.one").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserCannotReadTrainingCandidateQueue() throws Exception {
        mockMvc.perform(get("/api/permit-training-candidates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanReadTrainingCandidateQueue() throws Exception {
        when(trainingCandidateService.list(null, null, 0, 25))
                .thenReturn(new PagedResponse<>(
                        List.of(), 0, 25, 0, 0, false, false));

        mockMvc.perform(get("/api/permit-training-candidates")
                        .with(user("admin.one").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
