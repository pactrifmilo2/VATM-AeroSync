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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import vatm.aerosync.common.enums.FileSourceType;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_records")
public class FileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sync_job_id", nullable = false)
    private SyncJob syncJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private FileSourceType sourceType;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "stored_path", nullable = false)
    private String storedPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
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

    public FileSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(FileSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public void setStoredPath(String storedPath) {
        this.storedPath = storedPath;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
