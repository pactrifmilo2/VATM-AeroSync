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
