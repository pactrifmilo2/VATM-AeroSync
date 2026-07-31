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
import vatm.aerosync.common.enums.PermitReviewStatus;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "permit_reviews",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_permit_reviews_import",
                columnNames = "permit_import_id")
)
public class PermitReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_import_id", nullable = false)
    private PermitImport permitImport;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitReviewStatus status = PermitReviewStatus.PENDING;

    @Column(name = "profile_id", length = 120)
    private String profileId;

    @Column(name = "profile_version")
    private Integer profileVersion;

    private Double confidence;

    @Column(name = "runner_up_margin")
    private Double runnerUpMargin;

    @Column(name = "review_reason", length = 2000)
    private String reviewReason;

    @Lob
    @Column(name = "original_permit_json", nullable = false)
    private String originalPermitJson;

    @Lob
    @Column(name = "corrected_permit_json")
    private String correctedPermitJson;

    @Lob
    @Column(name = "published_permit_json")
    private String publishedPermitJson;

    @Lob
    @Column(name = "profile_candidates_json")
    private String profileCandidatesJson;

    @Lob
    @Column(name = "field_diagnostics_json")
    private String fieldDiagnosticsJson;

    @Lob
    @Column(name = "warnings_json")
    private String warningsJson;

    @Column(name = "correction_comment", length = 2000)
    private String correctionComment;

    @Column(name = "corrected_by", length = 100)
    private String correctedBy;

    @Column(name = "corrected_at")
    private LocalDateTime correctedAt;

    @Column(name = "approval_comment", length = 2000)
    private String approvalComment;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Column(name = "rejected_by", length = 100)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "publish_requested_by", length = 100)
    private String publishRequestedBy;

    @Column(name = "publish_requested_at")
    private LocalDateTime publishRequestedAt;

    @Column(name = "published_by", length = 100)
    private String publishedBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "publish_error", length = 2000)
    private String publishError;

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
            status = PermitReviewStatus.PENDING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public PermitImport getPermitImport() {
        return permitImport;
    }

    public void setPermitImport(PermitImport permitImport) {
        this.permitImport = permitImport;
    }

    public PermitReviewStatus getStatus() {
        return status;
    }

    public void setStatus(PermitReviewStatus status) {
        this.status = status;
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

    public Double getRunnerUpMargin() {
        return runnerUpMargin;
    }

    public void setRunnerUpMargin(Double runnerUpMargin) {
        this.runnerUpMargin = runnerUpMargin;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public String getOriginalPermitJson() {
        return originalPermitJson;
    }

    public void setOriginalPermitJson(String originalPermitJson) {
        this.originalPermitJson = originalPermitJson;
    }

    public String getCorrectedPermitJson() {
        return correctedPermitJson;
    }

    public void setCorrectedPermitJson(String correctedPermitJson) {
        this.correctedPermitJson = correctedPermitJson;
    }

    public String getPublishedPermitJson() {
        return publishedPermitJson;
    }

    public void setPublishedPermitJson(String publishedPermitJson) {
        this.publishedPermitJson = publishedPermitJson;
    }

    public String getProfileCandidatesJson() {
        return profileCandidatesJson;
    }

    public void setProfileCandidatesJson(String profileCandidatesJson) {
        this.profileCandidatesJson = profileCandidatesJson;
    }

    public String getFieldDiagnosticsJson() {
        return fieldDiagnosticsJson;
    }

    public void setFieldDiagnosticsJson(String fieldDiagnosticsJson) {
        this.fieldDiagnosticsJson = fieldDiagnosticsJson;
    }

    public String getWarningsJson() {
        return warningsJson;
    }

    public void setWarningsJson(String warningsJson) {
        this.warningsJson = warningsJson;
    }

    public String getCorrectionComment() {
        return correctionComment;
    }

    public void setCorrectionComment(String correctionComment) {
        this.correctionComment = correctionComment;
    }

    public String getCorrectedBy() {
        return correctedBy;
    }

    public void setCorrectedBy(String correctedBy) {
        this.correctedBy = correctedBy;
    }

    public LocalDateTime getCorrectedAt() {
        return correctedAt;
    }

    public void setCorrectedAt(LocalDateTime correctedAt) {
        this.correctedAt = correctedAt;
    }

    public String getApprovalComment() {
        return approvalComment;
    }

    public void setApprovalComment(String approvalComment) {
        this.approvalComment = approvalComment;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(String rejectedBy) {
        this.rejectedBy = rejectedBy;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(LocalDateTime rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getPublishRequestedBy() {
        return publishRequestedBy;
    }

    public void setPublishRequestedBy(String publishRequestedBy) {
        this.publishRequestedBy = publishRequestedBy;
    }

    public LocalDateTime getPublishRequestedAt() {
        return publishRequestedAt;
    }

    public void setPublishRequestedAt(LocalDateTime publishRequestedAt) {
        this.publishRequestedAt = publishRequestedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishError() {
        return publishError;
    }

    public void setPublishError(String publishError) {
        this.publishError = publishError;
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
