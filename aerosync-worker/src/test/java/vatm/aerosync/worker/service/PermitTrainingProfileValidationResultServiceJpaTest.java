package vatm.aerosync.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
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
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes =
        PermitTrainingProfileValidationResultServiceJpaTest
                .TestConfiguration.class)
class PermitTrainingProfileValidationResultServiceJpaTest {

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
    @Autowired
    private PermitTrainingProfileEventRepository eventRepository;

    @Test
    void successfulReplayStoresPreviewAndMovesOnlyToCanary() {
        PermitTrainingProfileVersion profile = saveProfile();
        PermitTrainingProfileEvidence evidence = saveEvidence(profile);
        PermitTrainingProfileValidationResultService service =
                new PermitTrainingProfileValidationResultService(
                        profileRepository,
                        evidenceRepository,
                        eventRepository,
                        new ObjectMapper().findAndRegisterModules());

        service.complete(
                profile.getId(),
                profile.getDefinitionChecksum(),
                "worker",
                "{\"schemaVersion\":1}",
                List.of(new PermitTrainingProfileValidationService
                        .ValidationItem(
                                evidence.getId(),
                                evidence.getTrainingSource().getId(),
                                true,
                                List.of())));

        PermitTrainingProfileVersion saved = profileRepository
                .findById(profile.getId()).orElseThrow();
        assertThat(saved.getStatus())
                .isEqualTo(PermitTrainingProfileStatus.CANARY);
        assertThat(saved.getStatus())
                .isNotEqualTo(PermitTrainingProfileStatus.ACTIVE);
        assertThat(saved.getCompiledProfileJson())
                .isEqualTo("{\"schemaVersion\":1}");
        assertThat(saved.getCanarySuccessCount()).isZero();
        assertThat(evidenceRepository.findById(evidence.getId()).orElseThrow()
                .getResult()).isEqualTo(PermitTrainingEvidenceResult.PASSED);
        assertThat(eventRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profile.getId()))
                .extracting(event -> event.getAction())
                .containsExactly("VALIDATION_PASSED");
    }

    @Test
    void failedReplayRequiresNewRevisionAndNeverActivates() {
        PermitTrainingProfileVersion profile = saveProfile();
        PermitTrainingProfileEvidence evidence = saveEvidence(profile);
        PermitTrainingProfileValidationResultService service =
                new PermitTrainingProfileValidationResultService(
                        profileRepository,
                        evidenceRepository,
                        eventRepository,
                        new ObjectMapper().findAndRegisterModules());

        service.complete(
                profile.getId(),
                profile.getDefinitionChecksum(),
                "worker",
                "{\"schemaVersion\":1}",
                List.of(new PermitTrainingProfileValidationService
                        .ValidationItem(
                                evidence.getId(),
                                evidence.getTrainingSource().getId(),
                                false,
                                List.of("operator.icao expected=QTR actual=ABC"))));

        PermitTrainingProfileVersion saved = profileRepository
                .findById(profile.getId()).orElseThrow();
        assertThat(saved.getStatus())
                .isEqualTo(PermitTrainingProfileStatus.NEEDS_REVISION);
        assertThat(saved.getStatus())
                .isNotEqualTo(PermitTrainingProfileStatus.ACTIVE);
        assertThat(saved.getLastError()).contains("1 of 1");
        assertThat(evidenceRepository.findById(evidence.getId()).orElseThrow()
                .getResult()).isEqualTo(PermitTrainingEvidenceResult.FAILED);
    }

    private PermitTrainingProfileVersion saveProfile() {
        PermitTrainingProfileVersion profile =
                new PermitTrainingProfileVersion();
        profile.setProfileKey("guided-qatar-cargo");
        profile.setProfileVersion(1);
        profile.setStatus(PermitTrainingProfileStatus.VALIDATING);
        profile.setSchemaVersion(1);
        profile.setDefinitionJson("{}");
        profile.setDefinitionChecksum("a".repeat(64));
        profile.setCreatedBy("operator.one");
        return profileRepository.saveAndFlush(profile);
    }

    private PermitTrainingProfileEvidence saveEvidence(
            PermitTrainingProfileVersion profile) {
        SyncJob job = new SyncJob();
        job.setFileHash("b".repeat(64));
        job.setStatus(SyncStatus.QUARANTINED);
        job = jobRepository.saveAndFlush(job);

        FileRecord file = new FileRecord();
        file.setSyncJob(job);
        file.setSourceType(FileSourceType.EMAIL);
        file.setOriginalFileName("permit.docx");
        file.setStoredPath("C:/archive/permit.docx");
        file = fileRepository.saveAndFlush(file);

        PermitTrainingSource source = new PermitTrainingSource();
        source.setFileRecord(file);
        source.setState(PermitTrainingSourceState.REVIEW_REQUIRED);
        source.setSourceHash(job.getFileHash());
        source.setOriginalFileName(file.getOriginalFileName());
        source.setDocumentJson("{}");
        source.setCorpusPath("C:/training/permit.docx");
        source.setRetainedAt(LocalDateTime.now());
        source = sourceRepository.saveAndFlush(source);

        PermitTrainingProfileEvidence evidence =
                new PermitTrainingProfileEvidence();
        evidence.setTrainingProfile(profile);
        evidence.setTrainingSource(source);
        evidence.setKind(PermitTrainingEvidenceKind.TRAINING);
        evidence.setResult(PermitTrainingEvidenceResult.CORRECTED);
        evidence.setExpectedSnapshotJson("{}");
        evidence.setActor("operator.one");
        return evidenceRepository.saveAndFlush(evidence);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("vatm.aerosync.common.entity")
    @EnableJpaRepositories("vatm.aerosync.common.repository")
    static class TestConfiguration {
    }
}
