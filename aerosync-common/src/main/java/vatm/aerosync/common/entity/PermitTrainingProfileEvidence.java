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
import jakarta.persistence.UniqueConstraint;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "permit_training_evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_training_profile_source",
                columnNames = {"training_profile_id", "training_source_id"})
)
public class PermitTrainingProfileEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "training_profile_id", nullable = false)
    private PermitTrainingProfileVersion trainingProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "training_source_id", nullable = false)
    private PermitTrainingSource trainingSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permit_review_id")
    private PermitReview permitReview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitTrainingEvidenceKind kind =
            PermitTrainingEvidenceKind.TRAINING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermitTrainingEvidenceResult result =
            PermitTrainingEvidenceResult.PENDING;

    @Lob
    @Column(name = "expected_snapshot_json")
    private String expectedSnapshotJson;

    @Column(length = 100)
    private String actor;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(length = 2000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (kind == null) {
            kind = PermitTrainingEvidenceKind.TRAINING;
        }
        if (result == null) {
            result = PermitTrainingEvidenceResult.PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public PermitTrainingProfileVersion getTrainingProfile() {
        return trainingProfile;
    }

    public void setTrainingProfile(PermitTrainingProfileVersion trainingProfile) {
        this.trainingProfile = trainingProfile;
    }

    public PermitTrainingSource getTrainingSource() {
        return trainingSource;
    }

    public void setTrainingSource(PermitTrainingSource trainingSource) {
        this.trainingSource = trainingSource;
    }

    public PermitReview getPermitReview() {
        return permitReview;
    }

    public void setPermitReview(PermitReview permitReview) {
        this.permitReview = permitReview;
    }

    public PermitTrainingEvidenceKind getKind() {
        return kind;
    }

    public void setKind(PermitTrainingEvidenceKind kind) {
        this.kind = kind;
    }

    public PermitTrainingEvidenceResult getResult() {
        return result;
    }

    public void setResult(PermitTrainingEvidenceResult result) {
        this.result = result;
    }

    public String getExpectedSnapshotJson() {
        return expectedSnapshotJson;
    }

    public void setExpectedSnapshotJson(String expectedSnapshotJson) {
        this.expectedSnapshotJson = expectedSnapshotJson;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
