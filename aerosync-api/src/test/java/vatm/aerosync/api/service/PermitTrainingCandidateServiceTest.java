package vatm.aerosync.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermitTrainingCandidateServiceTest {

    private PermitTrainingCandidateRepository repository;
    private PermitTrainingCandidateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PermitTrainingCandidateRepository.class);
        service = new PermitTrainingCandidateService(repository);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void approvedReviewCreatesOnlySafeHeaderAliasCandidates() {
        PermitReview review = approvedReview();
        review.setFieldDiagnosticsJson("""
                [
                  {
                    "field": "schedule.flightNumber",
                    "confidence": 0.92,
                    "source": "TABLE[2].HEADER[1..1].COLUMN[1]",
                    "method": "FUZZY_ALIAS",
                    "observedValue": "Flight No."
                  },
                  {
                    "field": "permitDate",
                    "confidence": 0.99,
                    "source": "PARAGRAPH_LINE[1]",
                    "method": "DATE_NEAR_LABEL",
                    "observedValue": "03/7/2026"
                  }
                ]
                """);

        service.captureFromApprovedReview(review);

        ArgumentCaptor<PermitTrainingCandidate> candidate =
                ArgumentCaptor.forClass(PermitTrainingCandidate.class);
        verify(repository).save(candidate.capture());
        assertThat(candidate.getValue().getSourceReview()).isSameAs(review);
        assertThat(candidate.getValue().getStatus())
                .isEqualTo(PermitTrainingStatus.PENDING);
        assertThat(candidate.getValue().getProfileId())
                .isEqualTo("spa066-vietnamese-landing-revision");
        assertThat(candidate.getValue().getSemanticField())
                .isEqualTo("schedule.flightNumber");
        assertThat(candidate.getValue().getAliasValue()).isEqualTo("Flight No.");
        assertThat(candidate.getValue().getCanonicalAlias()).isEqualTo("flightno");
    }

    @Test
    void approvedReviewCanPromoteAliasFromOlderStoredWarning() {
        PermitReview review = approvedReview();
        review.setFieldDiagnosticsJson("""
                [
                  {
                    "field": "schedule.etd",
                    "confidence": 0.91,
                    "source": "TABLE[2].HEADER[1..1].COLUMN[6]",
                    "method": "FUZZY_ALIAS"
                  }
                ]
                """);
        review.setWarningsJson("""
                [
                  {
                    "code": "FUZZY_ALIAS_USED",
                    "message": "schedule.etd fuzzily matched header 'Estimated Departure'",
                    "reviewRequired": true
                  }
                ]
                """);

        service.captureFromApprovedReview(review);

        ArgumentCaptor<PermitTrainingCandidate> candidate =
                ArgumentCaptor.forClass(PermitTrainingCandidate.class);
        verify(repository).save(candidate.capture());
        assertThat(candidate.getValue().getSemanticField()).isEqualTo("schedule.etd");
        assertThat(candidate.getValue().getAliasValue())
                .isEqualTo("Estimated Departure");
        assertThat(candidate.getValue().getConfidence()).isEqualTo(0.91);
    }

    @Test
    void adminApprovalIsSeparateAndRejectsCrossFieldAliasConflict() {
        PermitTrainingCandidate candidate = candidate();
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(candidate));
        when(repository.findByProfileIdAndProfileVersionAndCanonicalAliasAndStatus(
                candidate.getProfileId(),
                candidate.getProfileVersion(),
                candidate.getCanonicalAlias(),
                PermitTrainingStatus.APPROVED))
                .thenReturn(List.of());

        service.approve(9L, "Verified against source", "admin.one");

        assertThat(candidate.getStatus()).isEqualTo(PermitTrainingStatus.APPROVED);
        assertThat(candidate.getDecidedBy()).isEqualTo("admin.one");
        assertThat(candidate.getDecisionComment()).isEqualTo("Verified against source");

        PermitTrainingCandidate pending = candidate();
        PermitTrainingCandidate conflicting = candidate();
        conflicting.setSemanticField("schedule.etd");
        conflicting.setStatus(PermitTrainingStatus.APPROVED);
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(pending));
        when(repository.findByProfileIdAndProfileVersionAndCanonicalAliasAndStatus(
                pending.getProfileId(),
                pending.getProfileVersion(),
                pending.getCanonicalAlias(),
                PermitTrainingStatus.APPROVED))
                .thenReturn(List.of(conflicting));

        assertThatThrownBy(() ->
                service.approve(9L, null, "admin.one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another semantic field");
    }

    private PermitReview approvedReview() {
        PermitReview review = new PermitReview();
        ReflectionTestUtils.setField(review, "id", 4L);
        review.setStatus(PermitReviewStatus.APPROVED);
        review.setProfileId("spa066-vietnamese-landing-revision");
        review.setProfileVersion(1);
        review.setApprovedBy("operator.one");
        return review;
    }

    private PermitTrainingCandidate candidate() {
        PermitTrainingCandidate candidate = new PermitTrainingCandidate();
        ReflectionTestUtils.setField(candidate, "id", 9L);
        candidate.setSourceReview(approvedReview());
        candidate.setStatus(PermitTrainingStatus.PENDING);
        candidate.setProfileId("spa066-vietnamese-landing-revision");
        candidate.setProfileVersion(1);
        candidate.setSemanticField("schedule.flightNumber");
        candidate.setAliasValue("Flight No.");
        candidate.setCanonicalAlias("flightno");
        candidate.setMatchMethod("FUZZY_ALIAS");
        candidate.setConfidence(0.92);
        return candidate;
    }
}
