package vatm.aerosync.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingValidationCommand;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.worker.model.PermitFieldDiagnostic;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.model.WordPermitParseResult;
import vatm.aerosync.worker.pipeline.DocxSchedulePermitParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermitTrainingValidationServiceTest {

    @Test
    void retainedSourceReplaysAgainstCorrectedApprovedSnapshot()
            throws Exception {
        PermitTrainingCandidateRepository candidateRepository =
                mock(PermitTrainingCandidateRepository.class);
        FileRecordRepository fileRepository =
                mock(FileRecordRepository.class);
        DocxSchedulePermitParser parser =
                mock(DocxSchedulePermitParser.class);
        PermitTrainingValidationResultService resultService =
                mock(PermitTrainingValidationResultService.class);
        PermitReviewSnapshotMapper snapshotMapper =
                new PermitReviewSnapshotMapper();
        ObjectMapper objectMapper =
                new ObjectMapper().findAndRegisterModules();

        SyncJob job = new SyncJob();
        ReflectionTestUtils.setField(job, "id", 100L);
        PermitImport permitImport = new PermitImport();
        permitImport.setSyncJob(job);
        PermitReview review = new PermitReview();
        ReflectionTestUtils.setField(review, "id", 4L);
        review.setPermitImport(permitImport);
        PermitReviewSnapshot snapshot = snapshot("HVN");
        review.setOriginalPermitJson(
                objectMapper.writeValueAsString(snapshot("WRONG")));
        review.setCorrectedPermitJson(
                objectMapper.writeValueAsString(snapshot));
        PermitTrainingCandidate candidate = candidate(review);
        when(candidateRepository.findById(9L))
                .thenReturn(Optional.of(candidate));
        when(candidateRepository.findValidationGroup(
                candidate.getProfileId(),
                candidate.getProfileVersion(),
                candidate.getSemanticField(),
                candidate.getCanonicalAlias()))
                .thenReturn(List.of(candidate));

        Path retained = Files.createTempFile("training-corpus-", ".docx");
        FileRecord file = new FileRecord();
        ReflectionTestUtils.setField(file, "id", 8L);
        file.setSyncJob(job);
        file.setOriginalFileName("permit.docx");
        file.setStoredPath(retained.toString());
        when(fileRepository.findBySyncJobIdIn(List.of(100L)))
                .thenReturn(List.of(file));

        SchedulePermit permit = snapshotMapper.toPermit(snapshot);
        when(parser.parseWithTrainingCandidate(
                any(Path.class),
                org.mockito.ArgumentMatchers.eq("permit.docx"),
                org.mockito.ArgumentMatchers.same(candidate)))
                .thenReturn(new WordPermitParseResult(
                        permit,
                        candidate.getProfileId(),
                        candidate.getProfileVersion(),
                        1.0,
                        1.0,
                        false,
                        List.of(),
                        List.of(new PermitFieldDiagnostic(
                                candidate.getSemanticField(),
                                1.0,
                                "TABLE[1]",
                                "DECLARED_ALIAS",
                                candidate.getAliasValue())),
                        List.of()));
        PermitTrainingValidationService service =
                new PermitTrainingValidationService(
                        candidateRepository,
                        fileRepository,
                        parser,
                        snapshotMapper,
                        resultService,
                        objectMapper);

        service.validate(new PermitTrainingValidationCommand(
                9L, "admin.one", LocalDateTime.now()));

        verify(resultService).complete(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq("worker"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.contains("\"passed\":true"));
    }

    private PermitTrainingCandidate candidate(PermitReview review) {
        PermitTrainingCandidate candidate = new PermitTrainingCandidate();
        ReflectionTestUtils.setField(candidate, "id", 9L);
        candidate.setSourceReview(review);
        candidate.setStatus(PermitTrainingStatus.PENDING);
        candidate.setValidationStatus(
                PermitTrainingValidationStatus.RUNNING);
        candidate.setProfileId("profile-a");
        candidate.setProfileVersion(1);
        candidate.setSemanticField("schedule.flightNumber");
        candidate.setAliasValue("Flight No.");
        candidate.setCanonicalAlias("flightno");
        candidate.setMatchMethod("FUZZY_ALIAS");
        candidate.setConfidence(0.92);
        return candidate;
    }

    private PermitReviewSnapshot snapshot(String operatorIcao) {
        return new PermitReviewSnapshot(
                "LD-1",
                "LD-1",
                "1",
                "VATM",
                "LD",
                "1",
                "SUMMER",
                LocalDate.of(2026, 7, 1),
                operatorIcao,
                "REF",
                24,
                null,
                "S",
                false,
                false,
                "raw",
                List.of());
    }
}
