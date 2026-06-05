package vatm.aerosync.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.entity.DashboardAlert;
import vatm.aerosync.api.service.AlertService;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<DashboardAlert> listActiveAlerts() {
        return alertService.findActive();
    }
}
