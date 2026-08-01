package vatm.aerosync.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import vatm.aerosync.api.config.PermitTrainingProperties;
import vatm.aerosync.api.dto.PermitTrainingProfileCanaryRequest;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties =
        "app.permit-training.minimum-canary-successes=2")
@ContextConfiguration(classes =
        PermitTrainingProfileCanaryApiServiceJpaTest.TestConfiguration.class)
@Import({
        PermitTrainingProfileService.class,
        PermitTrainingProfileCanaryApiService.class
})
class PermitTrainingProfileCanaryApiServiceJpaTest {

    @Autowired
    private PermitTrainingProfileCanaryApiService service;
    @Autowired
    private SyncJobRepository jobRepository;
    @Autowired
    private FileRecordRepository fileRepository;
    @Autowired
    private PermitTrainingSourceRepository sourceRepository;
    @Autowired
    private PermitTrainingProfileVersionRepository profileRepository;
    @Autowired
    private PermitTrainingProfileEvidenceRepository evidenceRepository;

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void queuesOnlyAnUnseenRetainedSourceAndReportsReadiness()
            throws Exception {
        PermitTrainingSource training = saveSource("a", "a".repeat(64));
        PermitTrainingSource canary = saveSource("b", "b".repeat(64));
        PermitTrainingProfileVersion profile = saveProfile();
        saveTrainingEvidence(profile, training);
        long initialVersion = profile.getVersion();

        var response = service.requestCanary(
                profile.getId(),
                new PermitTrainingProfileCanaryRequest(
                        profile.getVersion(),
                        canary.getId(),
                        expectedPermit()),
                "operator.one");

        assertThat(response.status())
                .isEqualTo(PermitTrainingProfileStatus.CANARY);
        assertThat(response.status())
                .isNotEqualTo(PermitTrainingProfileStatus.ACTIVE);
        assertThat(response.evidence())
                .filteredOn(item -> item.kind()
                        == PermitTrainingEvidenceKind.CANARY)
                .singleElement()
                .satisfies(item -> assertThat(item.result())
                        .isEqualTo(PermitTrainingEvidenceResult.PENDING));
        assertThat(response.history())
                .extracting(event -> event.action())
                .contains("CANARY_REQUESTED");
        assertThat(response.version()).isGreaterThan(initialVersion);

        var readiness = service.readiness(profile.getId());
        assertThat(readiness.minimumSuccesses()).isEqualTo(2);
        assertThat(readiness.pendingCount()).isEqualTo(1);
        assertThat(readiness.readyForActivationReview()).isFalse();
        assertThat(readiness.blockers())
                .contains(
                        "CANARY_EVALUATION_PENDING",
                        "MINIMUM_CANARY_SUCCESSES_REQUIRED");
    }

    @Test
    void rejectsASecondFileWithTheSameHashAsTrainingEvidence()
            throws Exception {
        String trainingHash = "c".repeat(64);
        PermitTrainingSource training = saveSource("c", trainingHash);
        PermitTrainingSource duplicate = saveSource("d", trainingHash);
        PermitTrainingProfileVersion profile = saveProfile();
        saveTrainingEvidence(profile, training);

        assertThatThrownBy(() -> service.requestCanary(
                profile.getId(),
                new PermitTrainingProfileCanaryRequest(
                        profile.getVersion(),
                        duplicate.getId(),
                        expectedPermit()),
                "operator.one"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be unseen");
    }

    @Test
    void reportsReadyOnlyAfterTheConfiguredNumberOfCanariesPass()
            throws Exception {
        PermitTrainingProfileVersion profile = saveProfile();
        saveCanaryEvidence(profile, saveSource("f", "f".repeat(64)));
        saveCanaryEvidence(profile, saveSource("g", "g".repeat(64)));

        var readiness = service.readiness(profile.getId());

        assertThat(readiness.minimumSuccesses()).isEqualTo(2);
        assertThat(readiness.passedCount()).isEqualTo(2);
        assertThat(readiness.failedCount()).isZero();
        assertThat(readiness.pendingCount()).isZero();
        assertThat(readiness.readyForActivationReview()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
    }

    private PermitTrainingProfileVersion saveProfile() throws Exception {
        PermitTrainingProfileDefinition definition =
                new PermitTrainingProfileDefinition(
                        1,
                        "Qatar cargo permit",
                        "caav-english",
                        List.of(),
                        List.of(),
                        null);
        PermitTrainingProfileVersion profile =
                new PermitTrainingProfileVersion();
        profile.setProfileKey("guided-qatar-cargo");
        profile.setProfileVersion(1);
        profile.setStatus(PermitTrainingProfileStatus.CANARY);
        profile.setSchemaVersion(1);
        profile.setDefinitionJson(objectMapper.writeValueAsString(definition));
        profile.setDefinitionChecksum("e".repeat(64));
        profile.setCompiledProfileJson("{\"schemaVersion\":1}");
        profile.setCreatedBy("operator.one");
        return profileRepository.saveAndFlush(profile);
    }

    private void saveTrainingEvidence(
            PermitTrainingProfileVersion profile,
            PermitTrainingSource source) throws Exception {
        PermitTrainingProfileEvidence evidence =
                new PermitTrainingProfileEvidence();
        evidence.setTrainingProfile(profile);
        evidence.setTrainingSource(source);
        evidence.setKind(PermitTrainingEvidenceKind.TRAINING);
        evidence.setResult(PermitTrainingEvidenceResult.PASSED);
        evidence.setExpectedSnapshotJson(
                objectMapper.writeValueAsString(expectedPermit()));
        evidence.setActor("worker");
        evidenceRepository.saveAndFlush(evidence);
    }

    private void saveCanaryEvidence(
            PermitTrainingProfileVersion profile,
            PermitTrainingSource source) throws Exception {
        PermitTrainingProfileEvidence evidence =
                new PermitTrainingProfileEvidence();
        evidence.setTrainingProfile(profile);
        evidence.setTrainingSource(source);
        evidence.setKind(PermitTrainingEvidenceKind.CANARY);
        evidence.setResult(PermitTrainingEvidenceResult.PASSED);
        evidence.setExpectedSnapshotJson(
                objectMapper.writeValueAsString(expectedPermit()));
        evidence.setActor("worker");
        evidence.setEvaluatedAt(LocalDateTime.now());
        evidenceRepository.saveAndFlush(evidence);
    }

    private PermitTrainingSource saveSource(String seed, String sourceHash)
            throws Exception {
        SyncJob job = new SyncJob();
        job.setFileHash(seed.repeat(64));
        job.setStatus(SyncStatus.QUARANTINED);
        job = jobRepository.saveAndFlush(job);

        FileRecord file = new FileRecord();
        file.setSyncJob(job);
        file.setSourceType(FileSourceType.EMAIL);
        file.setOriginalFileName("permit-" + seed + ".docx");
        file.setStoredPath("C:/archive/permit-" + seed + ".docx");
        file = fileRepository.saveAndFlush(file);

        PermitTrainingSource source = new PermitTrainingSource();
        source.setFileRecord(file);
        source.setState(PermitTrainingSourceState.REVIEW_REQUIRED);
        source.setSourceHash(sourceHash);
        source.setOriginalFileName(file.getOriginalFileName());
        source.setDocumentJson("{\"tables\":[]}");
        source.setCorpusPath("C:/training/permit-" + seed + ".docx");
        source.setRetainedAt(LocalDateTime.now());
        return sourceRepository.saveAndFlush(source);
    }

    private PermitReviewSnapshot expectedPermit() {
        return new PermitReviewSnapshot(
                "LD-3000/07/2026VN",
                "LD 03000/S/CHK/2026",
                "3000",
                "CHK",
                "LD",
                "A",
                "S",
                LocalDate.of(2026, 7, 31),
                "QTR",
                null,
                24,
                null,
                "NO",
                false,
                false,
                "raw",
                List.of(new PermitReviewFlightSnapshot(
                        "CAR",
                        1L,
                        BigDecimal.ONE,
                        "QR9000",
                        null,
                        "---4---",
                        "OTHH",
                        "VVTS",
                        "0100",
                        "0850",
                        "R468",
                        LocalDate.of(2026, 8, 5),
                        LocalDate.of(2026, 8, 5),
                        null,
                        "77X")));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(PermitTrainingProperties.class)
    @EntityScan("vatm.aerosync.common.entity")
    @EnableJpaRepositories("vatm.aerosync.common.repository")
    static class TestConfiguration {
    }
}
