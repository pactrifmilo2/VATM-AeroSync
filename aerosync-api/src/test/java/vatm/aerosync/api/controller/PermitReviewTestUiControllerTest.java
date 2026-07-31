package vatm.aerosync.api.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PermitReviewTestUiControllerTest {

    private MockMvc mockMvc;
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PermitReviewTestUiController.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PermitReviewTestUiController())
                .build();
    }

    @Test
    void servesNoStoreTestConsoleWithRestrictiveBrowserPolicy() throws Exception {
        mockMvc.perform(get("/permit-review-test"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "AeroSync Permit Review Test Console")))
                .andExpect(header().string(
                        "Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("connect-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void servesLocalStylesheetAndScript() throws Exception {
        mockMvc.perform(get("/permit-review-test/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(".workspace")));

        mockMvc.perform(get("/permit-review-test/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "/api/permit-training-candidates")));
    }

    @Test
    void isNotRegisteredUnlessTestFlagIsEnabled() {
        contextRunner.run(context -> org.assertj.core.api.Assertions
                .assertThat(context)
                .doesNotHaveBean(PermitReviewTestUiController.class));

        contextRunner
                .withPropertyValues("app.permit-review-test-ui.enabled=true")
                .run(context -> org.assertj.core.api.Assertions
                        .assertThat(context)
                        .hasSingleBean(PermitReviewTestUiController.class));
    }
}
