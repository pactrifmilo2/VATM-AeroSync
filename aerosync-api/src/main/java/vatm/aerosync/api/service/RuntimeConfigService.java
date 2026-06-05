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
        entity.setIncomingDir(defaults.getIncomingDir());
        entity.setProcessedDir(defaults.getProcessedDir());
        entity.setErrorDir(defaults.getErrorDir());
        entity.setEmailHost(defaults.getEmailHost());
        entity.setEmailPort(defaults.getEmailPort());
        entity.setEmailProtocol(defaults.getEmailProtocol());
        entity.setEmailUser(defaults.getEmailUser());
        entity.setEmailPassword(defaults.getEmailPassword());
        entity.setRetryMode(defaults.getRetryMode());
        entity.setMaxSizePerFileMb(defaults.getMaxSizePerFileMb());
        entity.setAutoQuarantine(defaults.isAutoQuarantine());
        entity.setSkipDuplicateIdempotency(defaults.isSkipDuplicateIdempotency());
        entity.setSendZaloAlert(defaults.isSendZaloAlert());
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
        entity.setIncomingDir(dto.incomingDir());
        entity.setProcessedDir(dto.processedDir());
        entity.setErrorDir(dto.errorDir());
        entity.setEmailHost(dto.emailHost());
        entity.setEmailPort(dto.emailPort());
        entity.setEmailProtocol(dto.emailProtocol());
        entity.setEmailUser(dto.emailUser());
        entity.setEmailPassword(dto.emailPassword());
        entity.setRetryMode(dto.retryMode());
        entity.setMaxSizePerFileMb(dto.maxSizePerFileMb());
        entity.setAutoQuarantine(dto.autoQuarantine());
        entity.setSkipDuplicateIdempotency(dto.skipDuplicateIdempotency());
        entity.setSendZaloAlert(dto.sendZaloAlert());
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
                entity.getWhitelistSenders(),
                entity.getIncomingDir(),
                entity.getProcessedDir(),
                entity.getErrorDir(),
                entity.getEmailHost(),
                entity.getEmailPort(),
                entity.getEmailProtocol(),
                entity.getEmailUser(),
                entity.getEmailPassword(),
                entity.getRetryMode(),
                entity.getMaxSizePerFileMb(),
                entity.isAutoQuarantine(),
                entity.isSkipDuplicateIdempotency(),
                entity.isSendZaloAlert());
    }
}
