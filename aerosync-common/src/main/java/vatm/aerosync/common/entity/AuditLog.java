package vatm.aerosync.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id")
    private SyncJob syncJob;

    @Column(nullable = false, length = 100)
    private String action;

    @Lob
    @Column(name = "input_summary")
    private String inputSummary;

    @Lob
    @Column(name = "output_summary")
    private String outputSummary;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 32)
    private SyncStatus resultStatus;

    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @PrePersist
    void prePersist() {
        timestamp = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public SyncJob getSyncJob() {
        return syncJob;
    }

    public void setSyncJob(SyncJob syncJob) {
        this.syncJob = syncJob;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public SyncStatus getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(SyncStatus resultStatus) {
        this.resultStatus = resultStatus;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
