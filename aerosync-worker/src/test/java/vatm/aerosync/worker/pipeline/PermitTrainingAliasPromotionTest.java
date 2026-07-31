package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermitTrainingAliasPromotionTest {

    @Test
    void approvedCandidateBecomesTrustedAliasForMatchingProfileVersion() {
        PermitTrainingCandidateRepository repository =
                mock(PermitTrainingCandidateRepository.class);
        PermitTrainingCandidate candidate = candidate(1);
        when(repository.findAllByStatus(PermitTrainingStatus.APPROVED))
                .thenReturn(List.of(candidate));

        DocxPermitProfileCatalog catalog = new DocxPermitProfileCatalog(
                new PermitSemanticAliasCatalog(), repository);
        DocxPermitFormatProfile declared = catalog.activeProfiles()
                .declaredProfile("caav-english-overflight-scheduled");

        assertThat(declared.schedule().columns().get("flightNumber"))
                .contains("Flight identifier");
        WordPermitTableMatcher.ColumnResolution resolution =
                WordPermitTableMatcher.resolveSingleHeader(
                        List.of("Flight identifier"),
                        declared.schedule().columns());
        assertThat(resolution.matches().get("flightNumber").kind())
                .isEqualTo(WordPermitTableMatcher.MatchKind.DECLARED_ALIAS);
    }

    @Test
    void candidateFromOldProfileVersionIsNotActivated() {
        PermitTrainingCandidateRepository repository =
                mock(PermitTrainingCandidateRepository.class);
        when(repository.findAllByStatus(PermitTrainingStatus.APPROVED))
                .thenReturn(List.of(candidate(99)));

        DocxPermitProfileCatalog catalog = new DocxPermitProfileCatalog(
                new PermitSemanticAliasCatalog(), repository);
        DocxPermitFormatProfile declared = catalog.activeProfiles()
                .declaredProfile("caav-english-overflight-scheduled");

        assertThat(declared.schedule().columns().get("flightNumber"))
                .doesNotContain("Flight identifier");
    }

    @Test
    void pendingCandidateIsAvailableOnlyInValidationPreview() {
        PermitTrainingCandidateRepository repository =
                mock(PermitTrainingCandidateRepository.class);
        PermitTrainingCandidate pending = candidate(1);
        pending.setStatus(PermitTrainingStatus.PENDING);
        when(repository.findAllByStatus(PermitTrainingStatus.APPROVED))
                .thenReturn(List.of());

        DocxPermitProfileCatalog catalog = new DocxPermitProfileCatalog(
                new PermitSemanticAliasCatalog(), repository);

        assertThat(catalog.activeProfiles()
                .declaredProfile("caav-english-overflight-scheduled")
                .schedule().columns().get("flightNumber"))
                .doesNotContain("Flight identifier");
        assertThat(catalog.previewProfiles(pending)
                .declaredProfile("caav-english-overflight-scheduled")
                .schedule().columns().get("flightNumber"))
                .contains("Flight identifier");
    }

    private PermitTrainingCandidate candidate(int profileVersion) {
        PermitTrainingCandidate candidate = new PermitTrainingCandidate();
        candidate.setStatus(PermitTrainingStatus.APPROVED);
        candidate.setProfileId("caav-english-overflight-scheduled");
        candidate.setProfileVersion(profileVersion);
        candidate.setSemanticField("schedule.flightNumber");
        candidate.setAliasValue("Flight identifier");
        candidate.setCanonicalAlias("flightidentifier");
        candidate.setMatchMethod("SHARED_ALIAS");
        candidate.setConfidence(0.95);
        return candidate;
    }
}
