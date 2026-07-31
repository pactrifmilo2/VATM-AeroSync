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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "permit_training_candidates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_training_review_field",
                columnNames = {
                        "permit_review_id",
                        "semantic_field",
                        "canonical_alias"
                })
)
public class PermitTrainingCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_review_id", nullable = false)
    private PermitReview sourceReview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitTrainingStatus status = PermitTrainingStatus.PENDING;

    @Column(name = "profile_id", nullable = false, length = 120)
    private String profileId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "semantic_field", nullable = false, length = 120)
    private String semanticField;

    @Column(name = "alias_value", nullable = false, length = 500)
    private String aliasValue;

    @Column(name = "canonical_alias", nullable = false, length = 500)
    private String canonicalAlias;

    @Column(name = "match_method", nullable = false, length = 32)
    private String matchMethod;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "proposed_by", length = 100)
    private String proposedBy;

    @Column(name = "decision_comment", length = 2000)
    private String decisionComment;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "usage_count", nullable = false)
    private long usageCount;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 32)
    private PermitTrainingValidationStatus validationStatus =
            PermitTrainingValidationStatus.NOT_RUN;

    @Column(name = "validation_requested_by", length = 100)
    private String validationRequestedBy;

    @Column(name = "validation_requested_at")
    private LocalDateTime validationRequestedAt;

    @Column(name = "validation_completed_at")
    private LocalDateTime validationCompletedAt;

    @Column(name = "validation_corpus_size")
    private Integer validationCorpusSize;

    @Column(name = "validation_passed_count")
    private Integer validationPassedCount;

    @Column(name = "validation_failed_count")
    private Integer validationFailedCount;

    @Column(name = "validation_report", length = 4000)
    private String validationReport;

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
            status = PermitTrainingStatus.PENDING;
        }
        if (validationStatus == null) {
            validationStatus = PermitTrainingValidationStatus.NOT_RUN;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public PermitReview getSourceReview() {
        return sourceReview;
    }

    public void setSourceReview(PermitReview sourceReview) {
        this.sourceReview = sourceReview;
    }

    public PermitTrainingStatus getStatus() {
        return status;
    }

    public void setStatus(PermitTrainingStatus status) {
        this.status = status;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public int getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(int profileVersion) {
        this.profileVersion = profileVersion;
    }

    public String getSemanticField() {
        return semanticField;
    }

    public void setSemanticField(String semanticField) {
        this.semanticField = semanticField;
    }

    public String getAliasValue() {
        return aliasValue;
    }

    public void setAliasValue(String aliasValue) {
        this.aliasValue = aliasValue;
    }

    public String getCanonicalAlias() {
        return canonicalAlias;
    }

    public void setCanonicalAlias(String canonicalAlias) {
        this.canonicalAlias = canonicalAlias;
    }

    public String getMatchMethod() {
        return matchMethod;
    }

    public void setMatchMethod(String matchMethod) {
        this.matchMethod = matchMethod;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public void setProposedBy(String proposedBy) {
        this.proposedBy = proposedBy;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public long getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(long usageCount) {
        this.usageCount = usageCount;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public PermitTrainingValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(
            PermitTrainingValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getValidationRequestedBy() {
        return validationRequestedBy;
    }

    public void setValidationRequestedBy(String validationRequestedBy) {
        this.validationRequestedBy = validationRequestedBy;
    }

    public LocalDateTime getValidationRequestedAt() {
        return validationRequestedAt;
    }

    public void setValidationRequestedAt(LocalDateTime validationRequestedAt) {
        this.validationRequestedAt = validationRequestedAt;
    }

    public LocalDateTime getValidationCompletedAt() {
        return validationCompletedAt;
    }

    public void setValidationCompletedAt(LocalDateTime validationCompletedAt) {
        this.validationCompletedAt = validationCompletedAt;
    }

    public Integer getValidationCorpusSize() {
        return validationCorpusSize;
    }

    public void setValidationCorpusSize(Integer validationCorpusSize) {
        this.validationCorpusSize = validationCorpusSize;
    }

    public Integer getValidationPassedCount() {
        return validationPassedCount;
    }

    public void setValidationPassedCount(Integer validationPassedCount) {
        this.validationPassedCount = validationPassedCount;
    }

    public Integer getValidationFailedCount() {
        return validationFailedCount;
    }

    public void setValidationFailedCount(Integer validationFailedCount) {
        this.validationFailedCount = validationFailedCount;
    }

    public String getValidationReport() {
        return validationReport;
    }

    public void setValidationReport(String validationReport) {
        this.validationReport = validationReport;
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
