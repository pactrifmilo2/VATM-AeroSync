package vatm.aerosync.api.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.ApiProperties;
import vatm.aerosync.api.dto.RuntimeConfigDto;
import vatm.aerosync.api.entity.RuntimeConfigEntity;
import vatm.aerosync.api.repository.RuntimeConfigRepository;

@Service
public class RuntimeConfigService {

    private final RuntimeConfigRepository runtimeConfigRepository;
    private final ApiProperties apiProperties;

    public RuntimeConfigService(RuntimeConfigRepository runtimeConfigRepository, ApiProperties apiProperties) {
        this.runtimeConfigRepository = runtimeConfigRepository;
        this.apiProperties = apiProperties;
    }

    @PostConstruct
    @Transactional
    void ensureDefaults() {
        if (runtimeConfigRepository.existsById(RuntimeConfigEntity.SINGLETON_ID)) {
            return;
        }
        ApiProperties.Defaults defaults = apiProperties.getDefaults();
        RuntimeConfigEntity entity = new RuntimeConfigEntity();
        entity.setId(RuntimeConfigEntity.SINGLETON_ID);
        entity.setSchedulerFixedDelayMs(defaults.getSchedulerFixedDelayMs());
        entity.setMaxFilesPerCycle(defaults.getMaxFilesPerCycle());
        entity.setWhitelistSenders(defaults.getWhitelistSenders());
        runtimeConfigRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public RuntimeConfigDto getConfig() {
        return toDto(loadConfig());
    }

    @Transactional
    public RuntimeConfigDto updateConfig(RuntimeConfigDto dto) {
        RuntimeConfigEntity entity = loadConfig();
        entity.setSchedulerFixedDelayMs(dto.schedulerFixedDelayMs());
        entity.setMaxFilesPerCycle(dto.maxFilesPerCycle());
        entity.setWhitelistSenders(dto.whitelistSenders());
        runtimeConfigRepository.save(entity);
        return toDto(entity);
    }

    private RuntimeConfigEntity loadConfig() {
        return runtimeConfigRepository.findById(RuntimeConfigEntity.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Runtime config not initialized"));
    }

    private RuntimeConfigDto toDto(RuntimeConfigEntity entity) {
        return new RuntimeConfigDto(
                entity.getSchedulerFixedDelayMs(),
                entity.getMaxFilesPerCycle(),
                entity.getWhitelistSenders());
    }
}
