package vatm.aerosync.common.dto;

import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public class SyncResultEvent {

    private Long syncJobId;
    private SyncStatus status;
    private AlertLevel alertLevel;
    private String message;
    private LocalDateTime timestamp;

    public SyncResultEvent() {
    }

    public SyncResultEvent(Long syncJobId, SyncStatus status, AlertLevel alertLevel,
                           String message, LocalDateTime timestamp) {
        this.syncJobId = syncJobId;
        this.status = status;
        this.alertLevel = alertLevel;
        this.message = message;
        this.timestamp = timestamp;
    }

    public Long getSyncJobId() {
        return syncJobId;
    }

    public void setSyncJobId(Long syncJobId) {
        this.syncJobId = syncJobId;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public void setStatus(SyncStatus status) {
        this.status = status;
    }

    public AlertLevel getAlertLevel() {
        return alertLevel;
    }

    public void setAlertLevel(AlertLevel alertLevel) {
        this.alertLevel = alertLevel;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
