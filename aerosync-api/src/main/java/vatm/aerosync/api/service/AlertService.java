package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.entity.DashboardAlert;
import vatm.aerosync.api.repository.DashboardAlertRepository;
import vatm.aerosync.common.dto.SyncResultEvent;
import vatm.aerosync.common.enums.AlertLevel;

import java.util.List;

@Service
public class AlertService {

    private final DashboardAlertRepository alertRepository;

    public AlertService(DashboardAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional
    public void handleSyncResult(SyncResultEvent event) {
        if (event.getAlertLevel() == null || event.getAlertLevel() == AlertLevel.INFO) {
            return;
        }
        DashboardAlert alert = new DashboardAlert();
        alert.setSyncJobId(event.getSyncJobId());
        alert.setStatus(event.getStatus());
        alert.setAlertLevel(event.getAlertLevel());
        alert.setMessage(event.getMessage());
        alert.setEventTimestamp(event.getTimestamp());
        alertRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public List<DashboardAlert> findActive() {
        return alertRepository.findByResolvedFalseOrderByCreatedAtDesc();
    }

    public long countActive() {
        return alertRepository.countByResolvedFalse();
    }
}
