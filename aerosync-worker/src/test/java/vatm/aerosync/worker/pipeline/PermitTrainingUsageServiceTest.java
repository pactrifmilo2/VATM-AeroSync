package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.worker.model.PermitFieldDiagnostic;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermitTrainingUsageServiceTest {

    @Test
    void incrementsOnlyApprovedAliasObservedForItsSemanticField() {
        PermitTrainingCandidateRepository repository =
                mock(PermitTrainingCandidateRepository.class);
        PermitTrainingCandidate candidate = new PermitTrainingCandidate();
        ReflectionTestUtils.setField(candidate, "id", 9L);
        candidate.setStatus(PermitTrainingStatus.APPROVED);
        candidate.setProfileId("profile-a");
        candidate.setProfileVersion(2);
        candidate.setSemanticField("schedule.flightNumber");
        candidate.setAliasValue("Flight No.");
        candidate.setCanonicalAlias("flightno");
        when(repository.findByStatusAndProfileIdAndProfileVersion(
                PermitTrainingStatus.APPROVED, "profile-a", 2))
                .thenReturn(List.of(candidate));
        WordPermitParseResult result = new WordPermitParseResult(
                null,
                "profile-a",
                2,
                0.99,
                1.0,
                false,
                List.of(),
                List.of(new PermitFieldDiagnostic(
                        "schedule.flightNumber",
                        1.0,
                        "TABLE[1]",
                        "DECLARED_ALIAS",
                        "Flight No.")),
                List.of());

        new PermitTrainingUsageService(repository).record(result);

        verify(repository).incrementUsage(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(
                        PermitTrainingStatus.APPROVED),
                any(LocalDateTime.class));
    }
}
