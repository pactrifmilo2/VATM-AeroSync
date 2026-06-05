package vatm.aerosync.api.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.SyncJobDetailResponse;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.api.service.SyncJobService;
import vatm.aerosync.common.enums.SyncStatus;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @GetMapping
    public List<SyncJobSummaryResponse> listJobs(@RequestParam(required = false) SyncStatus status) {
        return syncJobService.listJobs(status);
    }

    @GetMapping("/{id}")
    public SyncJobDetailResponse getJob(@PathVariable Long id) {
        return syncJobService.getJob(id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryJob(@PathVariable Long id) {
        syncJobService.retryJob(id);
    }
}
