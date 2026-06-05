package vatm.aerosync.worker.service;

import org.springframework.stereotype.Service;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final SyncJobRepository syncJobRepository;

    public AuditLogService(AuditLogRepository auditLogRepository, SyncJobRepository syncJobRepository) {
        this.auditLogRepository = auditLogRepository;
        this.syncJobRepository = syncJobRepository;
    }

    public void record(Long syncJobId, String action, String inputSummary, String outputSummary,
                       SyncStatus resultStatus, long durationMs) {
        AuditLog log = new AuditLog();
        if (syncJobId != null) {
            SyncJob job = syncJobRepository.findById(syncJobId).orElse(null);
            log.setSyncJob(job);
        }
        log.setAction(action);
        log.setInputSummary(inputSummary);
        log.setOutputSummary(outputSummary);
        log.setDurationMs(durationMs);
        log.setResultStatus(resultStatus);
        auditLogRepository.save(log);
    }
}
