package vatm.aerosync.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "permit_training_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_training_profile_version",
                columnNames = {"profile_key", "profile_version"})
)
public class PermitTrainingProfileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_key", nullable = false, length = 120)
    private String profileKey;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitTrainingProfileStatus status =
            PermitTrainingProfileStatus.DRAFT;

    @Column(name = "base_profile_id", length = 120)
    private String baseProfileId;

    @Column(name = "base_profile_version")
    private Integer baseProfileVersion;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Lob
    @Column(name = "definition_json")
    private String definitionJson;

    @Lob
    @Column(name = "compiled_profile_json")
    private String compiledProfileJson;

    @Column(name = "definition_checksum", length = 64)
    private String definitionChecksum;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(name = "canary_success_count", nullable = false)
    private int canarySuccessCount;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "confirmed_by", length = 100)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

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
        if (status == null) {
            status = PermitTrainingProfileStatus.DRAFT;
        }
        if (schemaVersion < 1) {
            schemaVersion = 1;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getProfileKey() {
        return profileKey;
    }

    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey;
    }

    public int getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(int profileVersion) {
        this.profileVersion = profileVersion;
    }

    public PermitTrainingProfileStatus getStatus() {
        return status;
    }

    public void setStatus(PermitTrainingProfileStatus status) {
        this.status = status;
    }

    public String getBaseProfileId() {
        return baseProfileId;
    }

    public void setBaseProfileId(String baseProfileId) {
        this.baseProfileId = baseProfileId;
    }

    public Integer getBaseProfileVersion() {
        return baseProfileVersion;
    }

    public void setBaseProfileVersion(Integer baseProfileVersion) {
        this.baseProfileVersion = baseProfileVersion;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public void setDefinitionJson(String definitionJson) {
        this.definitionJson = definitionJson;
    }

    public String getCompiledProfileJson() {
        return compiledProfileJson;
    }

    public void setCompiledProfileJson(String compiledProfileJson) {
        this.compiledProfileJson = compiledProfileJson;
    }

    public String getDefinitionChecksum() {
        return definitionChecksum;
    }

    public void setDefinitionChecksum(String definitionChecksum) {
        this.definitionChecksum = definitionChecksum;
    }

    public int getEvidenceCount() {
        return evidenceCount;
    }

    public void setEvidenceCount(int evidenceCount) {
        this.evidenceCount = evidenceCount;
    }

    public int getCanarySuccessCount() {
        return canarySuccessCount;
    }

    public void setCanarySuccessCount(int canarySuccessCount) {
        this.canarySuccessCount = canarySuccessCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
