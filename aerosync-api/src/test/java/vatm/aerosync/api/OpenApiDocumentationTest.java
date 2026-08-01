package vatm.aerosync.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        AerosyncApiApplication.class,
        vatm.aerosync.api.config.ApiDataConfig.class
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsExposeEmailReportContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("VATM AeroSync API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"))
                .andExpect(jsonPath("$.paths['/api/reports/emails'].get").exists())
                .andExpect(jsonPath("$.paths['/api/reports/emails/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/reports/emails/summary'].get").exists())
                .andExpect(jsonPath("$.paths['/api/permit-reviews'].get").exists())
                .andExpect(jsonPath("$.paths['/api/permit-reviews/{id}/correction'].put").exists())
                .andExpect(jsonPath("$.paths['/api/permit-reviews/{id}/approve'].post").exists())
                .andExpect(jsonPath("$.paths['/api/permit-reviews/{id}/publish'].post").exists())
                .andExpect(jsonPath("$.paths['/api/permit-training-candidates'].get").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-candidates/{id}/approve'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-candidates/groups'].get")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-candidates/{id}/preflight'].get")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-candidates/{id}/validate'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-candidates/{id}/disable'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-candidates/{id}/history'].get")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-sources'].get")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-sources/{id}'].get")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-sources/{id}/retain'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/definition'].put")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/evidence'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/confirm'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/validate'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/compiled'].get")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/canaries'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/permit-training-profiles/{id}/canary-readiness'].get")
                        .exists());
    }

    @Test
    void swaggerUiRedirectsToItsWebApplication() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }
}
