package vatm.aerosync.api.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.RuntimeConfigDto;
import vatm.aerosync.api.service.RuntimeConfigService;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final RuntimeConfigService runtimeConfigService;

    public ConfigController(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping
    public RuntimeConfigDto getConfig() {
        return runtimeConfigService.getConfig();
    }

    @PutMapping
    public RuntimeConfigDto putConfig(@Valid @RequestBody RuntimeConfigDto config) {
        return runtimeConfigService.updateConfig(config);
    }
}
