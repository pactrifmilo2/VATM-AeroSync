package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.api.config.ApiProperties;
import vatm.aerosync.api.dto.RuntimeConfigDto;
import vatm.aerosync.api.entity.RuntimeConfigEntity;
import vatm.aerosync.api.repository.RuntimeConfigRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceTest {

    private final RuntimeConfigRepository repository = mock(RuntimeConfigRepository.class);
    private final ApiProperties apiProperties = new ApiProperties();
    private final RuntimeConfigService service = new RuntimeConfigService(repository, apiProperties);

    @Test
    void ensureDefaults_persistsFullDefaultConfig() {
        when(repository.existsById(RuntimeConfigEntity.SINGLETON_ID)).thenReturn(false);

        service.ensureDefaults();

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(entity ->
                entity.getSchedulerFixedDelayMs() == 300_000L
                        && entity.getMaxFilesPerCycle() == 100
                        && entity.getIncomingDir().equals("C:/vatm-storage/incoming")
                        && entity.getProcessedDir().equals("C:/vatm-storage/processed")
                        && entity.getErrorDir().equals("C:/vatm-storage/error")
                        && entity.getEmailHost().equals("mail.vatm.vn")
                        && entity.getEmailPort() == 993
                        && entity.getEmailProtocol().equals("IMAP SSL/TLS")
                        && entity.getEmailUser().equals("system_slb@vatm.vn")
                        && entity.getRetryMode().equals("Exponential")
                        && entity.getMaxSizePerFileMb() == 10
                        && entity.isAutoQuarantine()
                        && entity.isSkipDuplicateIdempotency()
                        && !entity.isSendZaloAlert()
                        && entity.getWhitelistSenders().equals(List.of("ops@vatm.local"))));
    }

    @Test
    void updateConfig_persistsFullConfig() {
        RuntimeConfigEntity entity = new RuntimeConfigEntity();
        when(repository.findById(RuntimeConfigEntity.SINGLETON_ID)).thenReturn(Optional.of(entity));
        RuntimeConfigDto dto = new RuntimeConfigDto(
                600_000L,
                50,
                List.of("finance@airline.com", "slot@caa.gov.vn"),
                "/data/incoming/",
                "/data/processed/",
                "/data/error/",
                "mail.vatm.vn",
                993,
                "IMAP SSL/TLS",
                "system_slb@vatm.vn",
                "secret",
                "Exponential",
                10,
                true,
                true,
                false);

        RuntimeConfigDto updated = service.updateConfig(dto);

        assertThat(updated).isEqualTo(dto);
        verify(repository).save(entity);
        assertThat(entity.getWhitelistSenders()).containsExactly("finance@airline.com", "slot@caa.gov.vn");
        assertThat(entity.getIncomingDir()).isEqualTo("/data/incoming/");
        assertThat(entity.getEmailHost()).isEqualTo("mail.vatm.vn");
    }
}
