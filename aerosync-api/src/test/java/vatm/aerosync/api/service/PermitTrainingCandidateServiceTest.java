package vatm.aerosync.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.api.config.PermitTrainingProperties;
import vatm.aerosync.common.dto.PermitTrainingValidationCommand;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.entity.PermitTrainingDecision;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.PermitTrainingAction;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.common.repository.PermitTrainingDecisionRepository;

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
    private PermitTrainingDecisionRepository decisionRepository;
    private PermitTrainingValidationPublisher validationPublisher;
    private PermitTrainingCandidateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PermitTrainingCandidateRepository.class);
        decisionRepository = mock(PermitTrainingDecisionRepository.class);
        validationPublisher = mock(PermitTrainingValidationPublisher.class);
        PermitTrainingProperties properties = new PermitTrainingProperties();
        properties.setMinimumEvidence(2);
        properties.setRequireCorpusValidation(true);
        service = new PermitTrainingCandidateService(
                repository,
                decisionRepository,
                properties,
                validationPublisher);
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(decisionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void approvedReviewCreatesOnlySafeHeaderAliasCandidates() {
        PermitReview review = approvedReview(4L);
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
        assertThat(candidate.getValue().getSemanticField())
                .isEqualTo("schedule.flightNumber");
        assertThat(candidate.getValue().getAliasValue())
                .isEqualTo("Flight No.");
        assertThat(candidate.getValue().getCanonicalAlias())
                .isEqualTo("flightno");
    }

    @Test
    void approvedReviewCanPromoteAliasFromOlderStoredWarning() {
        PermitReview review = approvedReview(4L);
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
        assertThat(candidate.getValue().getSemanticField())
                .isEqualTo("schedule.etd");
        assertThat(candidate.getValue().getAliasValue())
                .isEqualTo("Estimated Departure");
    }

    @Test
    void approvalRequiresIndependentEvidenceAndPassedReplay() {
        PermitTrainingCandidate first = candidate(9L, 4L);
        stubGroup(first, List.of(first));

        assertThatThrownBy(() ->
                service.approve(9L, null, "admin.one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSUFFICIENT_EVIDENCE");

        PermitTrainingCandidate second = candidate(10L, 5L);
        stubGroup(first, List.of(first, second));

        assertThatThrownBy(() ->
                service.approve(9L, null, "admin.one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORPUS_VALIDATION_REQUIRED");

        first.setValidationStatus(PermitTrainingValidationStatus.PASSED);
        service.approve(9L, "Replay checked", "admin.one");

        assertThat(first.getStatus())
                .isEqualTo(PermitTrainingStatus.APPROVED);
        ArgumentCaptor<PermitTrainingDecision> decision =
                ArgumentCaptor.forClass(PermitTrainingDecision.class);
        verify(decisionRepository).save(decision.capture());
        assertThat(decision.getValue().getAction())
                .isEqualTo(PermitTrainingAction.APPROVED);
    }

    @Test
    void approvalRejectsCrossFieldAliasConflict() {
        PermitTrainingCandidate first = candidate(9L, 4L);
        PermitTrainingCandidate second = candidate(10L, 5L);
        PermitTrainingCandidate conflict = candidate(11L, 6L);
        conflict.setSemanticField("schedule.etd");
        conflict.setStatus(PermitTrainingStatus.APPROVED);
        first.setValidationStatus(PermitTrainingValidationStatus.PASSED);
        stubGroup(first, List.of(first, second, conflict));

        assertThatThrownBy(() ->
                service.approve(9L, null, "admin.one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CROSS_FIELD_ALIAS_CONFLICT");
    }

    @Test
    void validationRequestQueuesWorkerReplay() {
        PermitTrainingCandidate first = candidate(9L, 4L);
        PermitTrainingCandidate second = candidate(10L, 5L);
        stubGroup(first, List.of(first, second));

        service.requestValidation(9L, "admin.one");

        assertThat(first.getValidationStatus())
                .isEqualTo(PermitTrainingValidationStatus.RUNNING);
        ArgumentCaptor<PermitTrainingValidationCommand> command =
                ArgumentCaptor.forClass(
                        PermitTrainingValidationCommand.class);
        verify(validationPublisher).publish(command.capture());
        assertThat(command.getValue().candidateId()).isEqualTo(9L);
    }

    @Test
    void disabledAliasNeedsFreshValidationBeforeReactivation() {
        PermitTrainingCandidate first = candidate(9L, 4L);
        PermitTrainingCandidate second = candidate(10L, 5L);
        first.setStatus(PermitTrainingStatus.APPROVED);
        first.setValidationStatus(PermitTrainingValidationStatus.PASSED);
        when(repository.findByIdForUpdate(9L))
                .thenReturn(Optional.of(first));
        stubExact(first, List.of(first, second));

        service.disable(9L, "Unexpected production match", "admin.one");

        assertThat(first.getStatus())
                .isEqualTo(PermitTrainingStatus.DISABLED);
        assertThat(first.getValidationStatus())
                .isEqualTo(PermitTrainingValidationStatus.NOT_RUN);

        stubGroup(first, List.of(first, second));
        assertThatThrownBy(() ->
                service.reactivate(9L, null, "admin.one"))
                .hasMessageContaining("CORPUS_VALIDATION_REQUIRED");

        first.setValidationStatus(PermitTrainingValidationStatus.PASSED);
        service.reactivate(9L, "Rechecked", "admin.one");
        assertThat(first.getStatus())
                .isEqualTo(PermitTrainingStatus.APPROVED);
    }

    private void stubGroup(
            PermitTrainingCandidate target,
            List<PermitTrainingCandidate> group) {
        when(repository.findById(target.getId()))
                .thenReturn(Optional.of(target));
        when(repository.findAliasGroupForUpdate(
                target.getProfileId(),
                target.getProfileVersion(),
                target.getCanonicalAlias()))
                .thenReturn(group);
        stubExact(target, group.stream()
                .filter(candidate -> candidate.getSemanticField()
                        .equals(target.getSemanticField()))
                .toList());
    }

    private void stubExact(
            PermitTrainingCandidate target,
            List<PermitTrainingCandidate> group) {
        when(repository
                .findByProfileIdAndProfileVersionAndSemanticFieldAndCanonicalAlias(
                        target.getProfileId(),
                        target.getProfileVersion(),
                        target.getSemanticField(),
                        target.getCanonicalAlias()))
                .thenReturn(group);
    }

    private PermitReview approvedReview(Long id) {
        PermitReview review = new PermitReview();
        ReflectionTestUtils.setField(review, "id", id);
        review.setStatus(PermitReviewStatus.APPROVED);
        review.setProfileId("spa066-vietnamese-landing-revision");
        review.setProfileVersion(1);
        review.setApprovedBy("operator.one");
        return review;
    }

    private PermitTrainingCandidate candidate(Long id, Long reviewId) {
        PermitTrainingCandidate candidate = new PermitTrainingCandidate();
        ReflectionTestUtils.setField(candidate, "id", id);
        candidate.setSourceReview(approvedReview(reviewId));
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
