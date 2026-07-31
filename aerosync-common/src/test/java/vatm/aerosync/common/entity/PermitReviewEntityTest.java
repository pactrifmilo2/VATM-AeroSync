package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingAction;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class PermitReviewEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsReviewWorkflowAndTrainingDecision() {
        SyncJob job = new SyncJob();
        job.setFileHash("a".repeat(64));
        job.setStatus(SyncStatus.FAILED);
        entityManager.persist(job);

        PermitImport permitImport = new PermitImport();
        permitImport.setSyncJob(job);
        permitImport.setNormalizedPermitId("O/F 05199/S/CHK/2026");
        permitImport.setSemanticHash("b".repeat(64));
        permitImport.setSourceFileHash(job.getFileHash());
        permitImport.setStatus(PermitImportStatus.REVISION_REVIEW);
        entityManager.persist(permitImport);

        PermitReview review = new PermitReview();
        review.setPermitImport(permitImport);
        review.setStatus(PermitReviewStatus.APPROVED);
        review.setProfileId("caav-english-overflight-scheduled");
        review.setProfileVersion(1);
        review.setConfidence(0.95);
        review.setOriginalPermitJson("{\"normalizedPermitId\":\"O/F 05199/S/CHK/2026\"}");
        review.setApprovedBy("operator.one");
        PermitReview persistedReview = entityManager.persistFlushFind(review);

        PermitTrainingCandidate trainingCandidate = new PermitTrainingCandidate();
        trainingCandidate.setSourceReview(persistedReview);
        trainingCandidate.setStatus(PermitTrainingStatus.APPROVED);
        trainingCandidate.setProfileId("caav-english-overflight-scheduled");
        trainingCandidate.setProfileVersion(1);
        trainingCandidate.setSemanticField("schedule.flightNumber");
        trainingCandidate.setAliasValue("Flight identifier");
        trainingCandidate.setCanonicalAlias("flightidentifier");
        trainingCandidate.setMatchMethod("SHARED_ALIAS");
        trainingCandidate.setConfidence(0.95);
        trainingCandidate.setProposedBy("operator.one");
        PermitTrainingCandidate persistedCandidate =
                entityManager.persistFlushFind(trainingCandidate);

        PermitTrainingDecision decision = new PermitTrainingDecision();
        decision.setCandidate(persistedCandidate);
        decision.setAction(PermitTrainingAction.APPROVED);
        decision.setActor("admin.one");
        decision.setComment("Two examples and replay passed");
        PermitTrainingDecision persistedDecision =
                entityManager.persistFlushFind(decision);

        assertThat(persistedReview.getStatus()).isEqualTo(PermitReviewStatus.APPROVED);
        assertThat(persistedReview.getPermitImport().getId()).isEqualTo(permitImport.getId());
        assertThat(persistedReview.getVersion()).isZero();
        assertThat(persistedReview.getCreatedAt()).isNotNull();
        assertThat(persistedCandidate.getStatus())
                .isEqualTo(PermitTrainingStatus.APPROVED);
        assertThat(persistedCandidate.getSourceReview().getId())
                .isEqualTo(persistedReview.getId());
        assertThat(persistedCandidate.getVersion()).isZero();
        assertThat(persistedCandidate.getUsageCount()).isZero();
        assertThat(persistedCandidate.getValidationStatus())
                .isEqualTo(PermitTrainingValidationStatus.NOT_RUN);
        assertThat(persistedDecision.getCandidate().getId())
                .isEqualTo(persistedCandidate.getId());
        assertThat(persistedDecision.getCreatedAt()).isNotNull();
    }

    @Test
    void persistsGuidedTrainingFoundationWithoutActivatingProfile() {
        SyncJob job = new SyncJob();
        job.setFileHash("c".repeat(64));
        job.setStatus(SyncStatus.QUARANTINED);
        entityManager.persist(job);

        FileRecord file = new FileRecord();
        file.setSyncJob(job);
        file.setSourceType(FileSourceType.EMAIL);
        file.setOriginalFileName("new-format.docx");
        file.setStoredPath("C:/archive/new-format.docx");
        entityManager.persist(file);

        PermitTrainingSource source = new PermitTrainingSource();
        source.setFileRecord(file);
        source.setState(PermitTrainingSourceState.REVIEW_REQUIRED);
        source.setSourceHash(job.getFileHash());
        source.setOriginalFileName(file.getOriginalFileName());
        source.setDocumentJson("{\"tables\":[]}");
        PermitTrainingSource persistedSource =
                entityManager.persistFlushFind(source);

        PermitTrainingProfileVersion profile =
                new PermitTrainingProfileVersion();
        profile.setProfileKey("guided-new-format");
        profile.setProfileVersion(1);
        profile.setStatus(PermitTrainingProfileStatus.DRAFT);
        profile.setCreatedBy("operator.one");
        profile.setDefinitionJson("{\"schemaVersion\":1}");
        PermitTrainingProfileVersion persistedProfile =
                entityManager.persistFlushFind(profile);

        PermitTrainingProfileEvidence evidence =
                new PermitTrainingProfileEvidence();
        evidence.setTrainingProfile(persistedProfile);
        evidence.setTrainingSource(persistedSource);
        evidence.setKind(PermitTrainingEvidenceKind.TRAINING);
        evidence.setResult(PermitTrainingEvidenceResult.CORRECTED);
        evidence.setExpectedSnapshotJson(
                "{\"operatorIcao\":\"QTR\"}");
        evidence.setActor("operator.one");
        PermitTrainingProfileEvidence persistedEvidence =
                entityManager.persistFlushFind(evidence);

        PermitTrainingProfileEvent event =
                new PermitTrainingProfileEvent();
        event.setTrainingProfile(persistedProfile);
        event.setAction("CREATED");
        event.setActor("operator.one");
        event.setEventDetail("{\"sourceId\":"
                + persistedSource.getId() + "}");
        PermitTrainingProfileEvent persistedEvent =
                entityManager.persistFlushFind(event);

        assertThat(persistedSource.getState())
                .isEqualTo(PermitTrainingSourceState.REVIEW_REQUIRED);
        assertThat(persistedProfile.getStatus())
                .isEqualTo(PermitTrainingProfileStatus.DRAFT);
        assertThat(persistedProfile.getStatus())
                .isNotEqualTo(PermitTrainingProfileStatus.ACTIVE);
        assertThat(persistedEvidence.getTrainingSource().getId())
                .isEqualTo(persistedSource.getId());
        assertThat(persistedEvent.getTrainingProfile().getId())
                .isEqualTo(persistedProfile.getId());
        assertThat(persistedEvent.getCreatedAt()).isNotNull();
    }
}
