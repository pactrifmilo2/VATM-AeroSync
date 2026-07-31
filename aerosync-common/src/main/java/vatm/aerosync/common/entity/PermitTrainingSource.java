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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import vatm.aerosync.common.enums.PermitTrainingSourceState;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "permit_training_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_training_source_file",
                columnNames = "file_record_id")
)
public class PermitTrainingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_record_id", nullable = false)
    private FileRecord fileRecord;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitTrainingSourceState state =
            PermitTrainingSourceState.PROCESSING;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "original_file_name", nullable = false, length = 500)
    private String originalFileName;

    @Lob
    @Column(name = "document_json")
    private String documentJson;

    @Column(name = "parse_error", length = 2000)
    private String parseError;

    @Column(name = "profile_id", length = 120)
    private String profileId;

    @Column(name = "profile_version")
    private Integer profileVersion;

    private Double confidence;

    @Column(name = "corpus_path", length = 1000)
    private String corpusPath;

    @Column(name = "retained_at")
    private LocalDateTime retainedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (state == null) {
            state = PermitTrainingSourceState.PROCESSING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public FileRecord getFileRecord() {
        return fileRecord;
    }

    public void setFileRecord(FileRecord fileRecord) {
        this.fileRecord = fileRecord;
    }

    public PermitTrainingSourceState getState() {
        return state;
    }

    public void setState(PermitTrainingSourceState state) {
        this.state = state;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getDocumentJson() {
        return documentJson;
    }

    public void setDocumentJson(String documentJson) {
        this.documentJson = documentJson;
    }

    public String getParseError() {
        return parseError;
    }

    public void setParseError(String parseError) {
        this.parseError = parseError;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public Integer getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(Integer profileVersion) {
        this.profileVersion = profileVersion;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getCorpusPath() {
        return corpusPath;
    }

    public void setCorpusPath(String corpusPath) {
        this.corpusPath = corpusPath;
    }

    public LocalDateTime getRetainedAt() {
        return retainedAt;
    }

    public void setRetainedAt(LocalDateTime retainedAt) {
        this.retainedAt = retainedAt;
    }

    public long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
