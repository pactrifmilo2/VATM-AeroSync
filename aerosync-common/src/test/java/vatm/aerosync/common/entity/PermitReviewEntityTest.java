package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.enums.UserRole;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class PermitReviewEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsReviewWorkflowAndDatabaseUserRole() {
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

        AppUser user = new AppUser();
        user.setUsername("operator.one");
        user.setPasswordHash("$2a$10$example");
        user.setRole(UserRole.OPERATOR);
        AppUser persistedUser = entityManager.persistFlushFind(user);

        assertThat(persistedReview.getStatus()).isEqualTo(PermitReviewStatus.APPROVED);
        assertThat(persistedReview.getPermitImport().getId()).isEqualTo(permitImport.getId());
        assertThat(persistedReview.getVersion()).isZero();
        assertThat(persistedReview.getCreatedAt()).isNotNull();
        assertThat(persistedUser.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(persistedUser.isEnabled()).isTrue();
    }
}
