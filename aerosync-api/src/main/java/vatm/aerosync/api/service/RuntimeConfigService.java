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
        java.util.Optional<RuntimeConfigEntity> existing =
                runtimeConfigRepository.findById(RuntimeConfigEntity.SINGLETON_ID);
        if (existing.isPresent()) {
            RuntimeConfigEntity entity = existing.get();
            if (!entity.isAllowlistInitialized()) {
                entity.setAllowedSenders(apiProperties.getDefaults().getAllowedSenders());
                entity.setAllowlistInitialized(true);
                runtimeConfigRepository.save(entity);
            }
            return;
        }
        ApiProperties.Defaults defaults = apiProperties.getDefaults();
        RuntimeConfigEntity entity = new RuntimeConfigEntity();
        entity.setId(RuntimeConfigEntity.SINGLETON_ID);
        entity.setSchedulerFixedDelayMs(defaults.getSchedulerFixedDelayMs());
        entity.setIngestionMode(defaults.getIngestionMode());
        entity.setFolderPollingIntervalMs(defaults.getFolderPollingIntervalMs());
        entity.setMaxFilesPerCycle(defaults.getMaxFilesPerCycle());
        entity.setBlacklistSenders(defaults.getBlacklistSenders());
        entity.setAllowedSenders(defaults.getAllowedSenders());
        entity.setAllowlistInitialized(true);
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
        entity.setIngestionMode(dto.ingestionMode());
        entity.setFolderPollingIntervalMs(dto.folderPollingIntervalMs());
        entity.setMaxFilesPerCycle(dto.maxFilesPerCycle());
        entity.setBlacklistSenders(dto.blacklistSenders());
        entity.setAllowedSenders(normalizeSenders(dto.allowedSenders()));
        entity.setAllowlistInitialized(true);
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
                entity.getIngestionMode() == null ? "EMAIL" : entity.getIngestionMode(),
                entity.getFolderPollingIntervalMs(),
                entity.getMaxFilesPerCycle(),
                entity.getBlacklistSenders(),
                entity.getAllowedSenders(),
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

    private java.util.List<String> normalizeSenders(java.util.List<String> senders) {
        if (senders == null) {
            return java.util.List.of();
        }
        return senders.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
    }
}
