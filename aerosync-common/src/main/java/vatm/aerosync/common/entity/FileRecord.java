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
import org.hibernate.annotations.ColumnDefault;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
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

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'DISCOVERED'")
    @Column(
            name = "processing_status",
            nullable = false,
            length = 32
    )
    private FileProcessingStatus processingStatus = FileProcessingStatus.DISCOVERED;

    @Column(name = "rows_saved")
    private Integer rowsSaved;

    @Column(name = "downloaded_at")
    private LocalDateTime downloadedAt;

    @Column(name = "database_saved_at")
    private LocalDateTime databaseSavedAt;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    @Column(
            name = "archive_status",
            nullable = false,
            length = 32
    )
    private FileArchiveStatus archiveStatus = FileArchiveStatus.PENDING;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(length = 64)
    private String checksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (processingStatus == null) {
            processingStatus = FileProcessingStatus.DISCOVERED;
        }
        if (archiveStatus == null) {
            archiveStatus = FileArchiveStatus.PENDING;
        }
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

    public FileProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(FileProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public Integer getRowsSaved() {
        return rowsSaved;
    }

    public void setRowsSaved(Integer rowsSaved) {
        this.rowsSaved = rowsSaved;
    }

    public LocalDateTime getDownloadedAt() {
        return downloadedAt;
    }

    public void setDownloadedAt(LocalDateTime downloadedAt) {
        this.downloadedAt = downloadedAt;
    }

    public LocalDateTime getDatabaseSavedAt() {
        return databaseSavedAt;
    }

    public void setDatabaseSavedAt(LocalDateTime databaseSavedAt) {
        this.databaseSavedAt = databaseSavedAt;
    }

    public FileArchiveStatus getArchiveStatus() {
        return archiveStatus;
    }

    public void setArchiveStatus(FileArchiveStatus archiveStatus) {
        this.archiveStatus = archiveStatus;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
