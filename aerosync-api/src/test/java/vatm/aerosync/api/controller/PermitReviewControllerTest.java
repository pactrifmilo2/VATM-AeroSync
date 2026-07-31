package vatm.aerosync.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vatm.aerosync.api.AerosyncApiApplication;
import vatm.aerosync.api.config.ApiDataConfig;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.security.LegacyTUserAccount;
import vatm.aerosync.api.security.LegacyTUserAccountRepository;
import vatm.aerosync.api.security.LegacyTUsersPasswordEncoder;
import vatm.aerosync.api.service.PermitReviewService;
import vatm.aerosync.api.service.PermitTrainingCandidateService;
import vatm.aerosync.api.service.PermitTrainingProfileService;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {AerosyncApiApplication.class, ApiDataConfig.class},
        properties = "app.permit-review-test-ui.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermitReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PermitReviewService permitReviewService;

    @MockitoBean
    private PermitTrainingCandidateService trainingCandidateService;

    @MockitoBean
    private PermitTrainingProfileService trainingProfileService;

    @MockitoBean
    private LegacyTUserAccountRepository legacyTUserAccountRepository;

    @Test
    void anonymousUserCannotReadReviewQueue() throws Exception {
        mockMvc.perform(get("/api/permit-reviews"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUserCannotOpenReviewTestConsole() throws Exception {
        mockMvc.perform(get("/permit-review-test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void administratorSignsInOnceAndReusesServerSession() throws Exception {
        String passwordHash =
                new LegacyTUsersPasswordEncoder().encode("test-password");
        when(legacyTUserAccountRepository.findByUsernameIgnoreCase("admin"))
                .thenReturn(Optional.of(new LegacyTUserAccount(
                        1L,
                        "admin",
                        passwordHash,
                        true,
                        true,
                        true)));

        MvcResult loginResult = mockMvc.perform(formLogin()
                        .user("admin")
                        .password("test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/permit-review-test"))
                .andReturn();
        MockHttpSession session =
                (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/permit-review-test").session(session))
                .andExpect(status().isOk());

        when(trainingCandidateService.list(null, null, 0, 25))
                .thenReturn(new PagedResponse<>(
                        List.of(), 0, 25, 0, 0, false, false));
        mockMvc.perform(get("/api/permit-training-candidates")
                        .session(session))
                .andExpect(status().isOk());
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
    void anonymousUserCannotReadGuidedTrainingProfiles() throws Exception {
        mockMvc.perform(get("/api/permit-training-profiles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorCanCreateGuidedDraftWithoutActivatingIt() throws Exception {
        mockMvc.perform(post("/api/permit-training-profiles")
                        .contentType("application/json")
                        .content("""
                                {
                                  "profileKey":"guided-qatar-cargo",
                                  "displayName":"Qatar cargo permit",
                                  "family":"caav-english",
                                  "sourceId":11
                                }
                                """)
                        .with(user("operator.one").roles("OPERATOR")))
                .andExpect(status().isCreated());

        verify(trainingProfileService).create(
                any(), eq("operator.one"));
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

    @Test
    void adminCanRequestTrainingCorpusValidation() throws Exception {
        mockMvc.perform(post(
                        "/api/permit-training-candidates/9/validate")
                        .with(user("admin.one").roles("ADMIN")))
                .andExpect(status().isAccepted());

        verify(trainingCandidateService)
                .requestValidation(9L, "admin.one");
    }

    @Test
    void operatorCannotDisableTrainingAlias() throws Exception {
        mockMvc.perform(post(
                        "/api/permit-training-candidates/9/disable")
                        .contentType("application/json")
                        .content("""
                                {"comment":"Bad match"}
                                """)
                        .with(user("operator.one").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }
}
