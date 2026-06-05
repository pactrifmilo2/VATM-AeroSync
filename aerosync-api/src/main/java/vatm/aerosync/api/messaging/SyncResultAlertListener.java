package vatm.aerosync.api.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.api.service.AlertService;
import vatm.aerosync.common.dto.SyncResultEvent;

@Component
public class SyncResultAlertListener {

    private final AlertService alertService;

    public SyncResultAlertListener(AlertService alertService) {
        this.alertService = alertService;
    }

    @RabbitListener(queues = "${app.rabbit.dashboard-alerts-queue}")
    public void onSyncResult(SyncResultEvent event) {
        alertService.handleSyncResult(event);
    }
}
