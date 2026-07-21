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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vatm.aerosync.common.enums.PermitImportStatus;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "permit_imports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_permit_imports_sync_job",
                columnNames = "sync_job_id")
)
public class PermitImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sync_job_id", nullable = false)
    private SyncJob syncJob;

    @Column(name = "normalized_permit_id", nullable = false, length = 100)
    private String normalizedPermitId;

    @Column(name = "semantic_hash", nullable = false, length = 64)
    private String semanticHash;

    @Column(name = "source_file_hash", nullable = false, length = 64)
    private String sourceFileHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitImportStatus status = PermitImportStatus.RESERVED;

    @Column(name = "target_master_id")
    private Long targetMasterId;

    @Column(name = "target_perm_id")
    private Long targetPermId;

    @Column(name = "detail_count")
    private Integer detailCount;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = PermitImportStatus.RESERVED;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
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

    public String getNormalizedPermitId() {
        return normalizedPermitId;
    }

    public void setNormalizedPermitId(String normalizedPermitId) {
        this.normalizedPermitId = normalizedPermitId;
    }

    public String getSemanticHash() {
        return semanticHash;
    }

    public void setSemanticHash(String semanticHash) {
        this.semanticHash = semanticHash;
    }

    public String getSourceFileHash() {
        return sourceFileHash;
    }

    public void setSourceFileHash(String sourceFileHash) {
        this.sourceFileHash = sourceFileHash;
    }

    public PermitImportStatus getStatus() {
        return status;
    }

    public void setStatus(PermitImportStatus status) {
        this.status = status;
    }

    public Long getTargetMasterId() {
        return targetMasterId;
    }

    public void setTargetMasterId(Long targetMasterId) {
        this.targetMasterId = targetMasterId;
    }

    public Long getTargetPermId() {
        return targetPermId;
    }

    public void setTargetPermId(Long targetPermId) {
        this.targetPermId = targetPermId;
    }

    public Integer getDetailCount() {
        return detailCount;
    }

    public void setDetailCount(Integer detailCount) {
        this.detailCount = detailCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
