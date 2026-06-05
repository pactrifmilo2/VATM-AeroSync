package vatm.aerosync.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.api.entity.DashboardAlert;
import vatm.aerosync.api.repository.DashboardAlertRepository;
import vatm.aerosync.common.dto.SyncResultEvent;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private DashboardAlertRepository alertRepository;

    @Captor
    private ArgumentCaptor<DashboardAlert> alertCaptor;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new AlertService(alertRepository);
    }

    @Test
    void handleSyncResult_persistsWarningAndCriticalAlerts() {
        SyncResultEvent warning = new SyncResultEvent(
                1L, SyncStatus.FAILED, AlertLevel.WARNING, "retry pending", LocalDateTime.now());
        SyncResultEvent critical = new SyncResultEvent(
                2L, SyncStatus.FAILED, AlertLevel.CRITICAL, "email down", LocalDateTime.now());

        alertService.handleSyncResult(warning);
        alertService.handleSyncResult(critical);

        verify(alertRepository, org.mockito.Mockito.times(2)).save(alertCaptor.capture());
        List<DashboardAlert> saved = alertCaptor.getAllValues();
        assertThat(saved).extracting(DashboardAlert::getAlertLevel)
                .containsExactly(AlertLevel.WARNING, AlertLevel.CRITICAL);
    }

    @Test
    void handleSyncResult_skipsInfoLevel() {
        SyncResultEvent info = new SyncResultEvent(
                3L, SyncStatus.SUCCESS, AlertLevel.INFO, "done", LocalDateTime.now());

        alertService.handleSyncResult(info);

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findActive_returnsUnresolvedWarningAndCritical() {
        DashboardAlert active = new DashboardAlert();
        active.setResolved(false);
        when(alertRepository.findByResolvedFalseOrderByCreatedAtDesc())
                .thenReturn(List.of(active));

        assertThat(alertService.findActive()).containsExactly(active);
    }
}
