package vatm.aerosync.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vatm.aerosync.api.dto.RuntimeConfigDto;
import vatm.aerosync.api.service.RuntimeConfigService;
import vatm.aerosync.api.web.ApiExceptionHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
@Import(ApiExceptionHandler.class)
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private RuntimeConfigService runtimeConfigService;

    @Test
    void getConfig_returnsCurrentSettings() throws Exception {
        RuntimeConfigDto config = new RuntimeConfigDto(300_000L, 100, List.of("ops@vatm.local"));
        when(runtimeConfigService.getConfig()).thenReturn(config);

        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulerFixedDelayMs").value(300000))
                .andExpect(jsonPath("$.maxFilesPerCycle").value(100))
                .andExpect(jsonPath("$.whitelistSenders[0]").value("ops@vatm.local"));
    }

    @Test
    void putConfig_validatesAndUpdates() throws Exception {
        RuntimeConfigDto updated = new RuntimeConfigDto(600_000L, 50, List.of("vip@vatm.local"));
        when(runtimeConfigService.updateConfig(any())).thenReturn(updated);

        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulerFixedDelayMs").value(600000))
                .andExpect(jsonPath("$.maxFilesPerCycle").value(50));
    }

    @Test
    void putConfig_rejectsInvalidRateLimit() throws Exception {
        RuntimeConfigDto invalid = new RuntimeConfigDto(300_000L, 0, List.of("ops@vatm.local"));

        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }
}
