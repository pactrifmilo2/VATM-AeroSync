package vatm.aerosync.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.api.dto.PermitTrainingProfileCreateRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileEvidenceRequest;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(
        classes = PermitTrainingProfileServiceJpaTest.TestConfiguration.class)
@Import({
        PermitTrainingProfileService.class,
        PermitTrainingProfileValidationApiService.class
})
class PermitTrainingProfileServiceJpaTest {

    @Autowired
    private PermitTrainingProfileService service;
    @Autowired
    private PermitTrainingProfileValidationApiService validationService;
    @Autowired
    private SyncJobRepository syncJobRepository;
    @Autowired
    private FileRecordRepository fileRecordRepository;
    @Autowired
    private PermitTrainingSourceRepository sourceRepository;
    @Autowired
    private PermitTrainingProfileVersionRepository profileRepository;

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void operatorBuildsAndConfirmsVersionedDraftWithoutActivation()
            throws Exception {
        PermitTrainingSource source = saveSource("d");

        var created = service.create(
                new PermitTrainingProfileCreateRequest(
                        "guided-qatar-cargo",
                        "Qatar cargo permit",
                        "caav-english",
                        "caav-generic-landing-issued",
                        source.getId()),
                "operator.one");

        assertThat(created.profileVersion()).isEqualTo(1);
        assertThat(created.status())
                .isEqualTo(PermitTrainingProfileStatus.DRAFT);
        assertThat(created.evidence()).hasSize(1);
        assertThat(created.evidence().getFirst().expectedPermit()).isNull();

        var mapped = service.updateDefinition(
                created.id(),
                created.version(),
                completeDefinition(),
                "operator.one");
        assertThat(mapped.definition().tables()).hasSize(1);
        assertThat(mapped.definition().fields()).hasSize(3);
        assertThat(mapped.definitionChecksum()).hasSize(64);

        var withEvidence = service.attachEvidence(
                created.id(),
                new PermitTrainingProfileEvidenceRequest(
                        mapped.version(),
                        source.getId(),
                        null,
                        expectedPermit()),
                "operator.one");
        assertThat(withEvidence.evidenceCount()).isEqualTo(1);
        assertThat(withEvidence.evidence().getFirst().expectedPermit()
                .operatorId()).isEqualTo("QTR");

        var confirmed = service.confirmMapping(
                created.id(),
                withEvidence.version(),
                "operator.two");

        assertThat(confirmed.status())
                .isEqualTo(PermitTrainingProfileStatus.COLLECTING_EVIDENCE);
        assertThat(confirmed.status())
                .isNotEqualTo(PermitTrainingProfileStatus.ACTIVE);
        assertThat(confirmed.confirmedBy()).isEqualTo("operator.two");
        assertThat(confirmed.history())
                .extracting(event -> event.action())
                .containsExactly(
                        "CREATED",
                        "DEFINITION_UPDATED",
                        "EVIDENCE_ATTACHED",
                        "MAPPING_CONFIRMED");
        assertThat(profileRepository.findById(created.id()).orElseThrow()
                .getCompiledProfileJson()).isNull();

        PermitTrainingSource secondSource = saveSource("e");
        var collecting = service.attachEvidence(
                created.id(),
                new PermitTrainingProfileEvidenceRequest(
                        confirmed.version(),
                        secondSource.getId(),
                        null,
                        expectedPermit()),
                "operator.two");
        assertThat(collecting.status())
                .isEqualTo(PermitTrainingProfileStatus.COLLECTING_EVIDENCE);
        assertThat(collecting.evidenceCount()).isEqualTo(2);

        var validating = validationService.requestValidation(
                created.id(),
                collecting.version(),
                "operator.two");
        assertThat(validating.status())
                .isEqualTo(PermitTrainingProfileStatus.VALIDATING);
        assertThat(validating.status())
                .isNotEqualTo(PermitTrainingProfileStatus.ACTIVE);
        assertThat(validating.history())
                .extracting(event -> event.action())
                .contains("VALIDATION_REQUESTED");
        PermitTrainingSource versionSource = saveSource("g");
        var secondVersion = service.create(
                new PermitTrainingProfileCreateRequest(
                        "guided-qatar-cargo",
                        "Qatar cargo permit revision",
                        "caav-english",
                        null,
                        versionSource.getId()),
                "operator.one");
        assertThat(secondVersion.profileVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAHeaderCellThatIsNotInCapturedDocument()
            throws Exception {
        PermitTrainingSource source = saveSource("f");
        var created = service.create(
                new PermitTrainingProfileCreateRequest(
                        "guided-invalid-cell",
                        "Invalid cell test",
                        "caav-english",
                        null,
                        source.getId()),
                "operator.one");
        PermitTrainingProfileDefinition definition = completeDefinition();
        Map<String, String> invalidColumns = new LinkedHashMap<>(
                definition.tables().getFirst().columns());
        invalidColumns.put("flightNumber", "table-9-row-0-cell-0");
        PermitTrainingProfileDefinition invalid =
                new PermitTrainingProfileDefinition(
                        definition.schemaVersion(),
                        definition.displayName(),
                        definition.family(),
                        definition.fields(),
                        List.of(new PermitTrainingProfileDefinition.TableMapping(
                                PermitTrainingProfileDefinition.TableRole.SCHEDULE,
                                0,
                                1,
                                invalidColumns)),
                        definition.options());

        assertThatThrownBy(() -> service.updateDefinition(
                created.id(),
                created.version(),
                invalid,
                "operator.one"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown training source cell");
    }

    private PermitTrainingSource saveSource(String hashSeed)
            throws Exception {
        SyncJob job = new SyncJob();
        job.setFileHash(hashSeed.repeat(64));
        job.setStatus(SyncStatus.QUARANTINED);
        job = syncJobRepository.saveAndFlush(job);

        FileRecord file = new FileRecord();
        file.setSyncJob(job);
        file.setSourceType(FileSourceType.EMAIL);
        file.setOriginalFileName("permit-" + hashSeed + ".docx");
        file.setStoredPath("C:/archive/permit-" + hashSeed + ".docx");
        file = fileRecordRepository.saveAndFlush(file);

        PermitTrainingSource source = new PermitTrainingSource();
        source.setFileRecord(file);
        source.setState(PermitTrainingSourceState.REVIEW_REQUIRED);
        source.setSourceHash(job.getFileHash());
        source.setOriginalFileName(file.getOriginalFileName());
        source.setDocumentJson(objectMapper.writeValueAsString(document()));
        source.setCorpusPath("C:/training/" + hashSeed + ".docx");
        source.setRetainedAt(LocalDateTime.now());
        return sourceRepository.saveAndFlush(source);
    }

    private PermitTrainingDocument document() {
        List<String> headers = List.of(
                "Flight number",
                "Effective from",
                "Effective to",
                "Days of services",
                "Departure Airport",
                "ETD",
                "Arrival Airport");
        List<String> values = List.of(
                "QR8364",
                "04AUG26",
                "04AUG26",
                "-2-----",
                "DOH",
                "0100",
                "SGN");
        return new PermitTrainingDocument(
                "Hanoi, 31/7/2026\nPermit No.: LD-2838/06/2026VN",
                String.join("\n", headers) + "\n" + String.join("\n", values),
                "Hanoi, 31/7/2026\nPermit No.: LD-2838/06/2026VN\n"
                        + String.join("\n", headers) + "\n"
                        + String.join("\n", values),
                List.of(new PermitTrainingDocument.Table(
                        0,
                        "2. Schedules (UTC Time)",
                        List.of(
                                row(0, headers),
                                row(1, values)))),
                LocalDate.of(2026, 7, 31));
    }

    private PermitTrainingDocument.Row row(
            int rowIndex,
            List<String> values) {
        List<PermitTrainingDocument.Cell> cells = new java.util.ArrayList<>();
        for (int column = 0; column < values.size(); column++) {
            cells.add(new PermitTrainingDocument.Cell(
                    "table-0-row-" + rowIndex + "-cell-" + column,
                    rowIndex,
                    column,
                    values.get(column)));
        }
        return new PermitTrainingDocument.Row(rowIndex, cells);
    }

    private PermitTrainingProfileDefinition completeDefinition() {
        Map<String, String> columns = new LinkedHashMap<>();
        List<String> semanticColumns = List.of(
                "flightNumber",
                "effectiveFrom",
                "effectiveTo",
                "serviceDays",
                "fromAirport",
                "etd",
                "toAirport");
        for (int index = 0; index < semanticColumns.size(); index++) {
            columns.put(
                    semanticColumns.get(index),
                    "table-0-row-0-cell-" + index);
        }
        return new PermitTrainingProfileDefinition(
                1,
                "Qatar cargo permit",
                "caav-english",
                List.of(
                        textField(
                                "permit.sourceNumber",
                                "Permit No.: LD-2838/06/2026VN",
                                "LD-2838/06/2026VN"),
                        textField(
                                "permit.date",
                                "31/7/2026",
                                "2026-07-31"),
                        new PermitTrainingProfileDefinition.FieldMapping(
                                "operator.icao",
                                PermitTrainingProfileDefinition.SourceKind.CONSTANT,
                                null,
                                null,
                                "QTR",
                                true)),
                List.of(new PermitTrainingProfileDefinition.TableMapping(
                        PermitTrainingProfileDefinition.TableRole.SCHEDULE,
                        0,
                        1,
                        columns)),
                new PermitTrainingProfileDefinition.Options(
                        "CHK",
                        "LD",
                        "A",
                        "S",
                        24,
                        "NO",
                        false,
                        false,
                        true));
    }

    private PermitTrainingProfileDefinition.FieldMapping textField(
            String semanticField,
            String selectedText,
            String confirmedValue) {
        return new PermitTrainingProfileDefinition.FieldMapping(
                semanticField,
                PermitTrainingProfileDefinition.SourceKind.TEXT,
                null,
                selectedText,
                confirmedValue,
                true);
    }

    private PermitReviewSnapshot expectedPermit() {
        return new PermitReviewSnapshot(
                "LD-2838/06/2026VN",
                "LD 02838/S/CHK/2026",
                "2838",
                "CHK",
                "LD",
                "A",
                "S",
                LocalDate.of(2026, 7, 31),
                "QTR",
                null,
                24,
                "P.O BOX 22550, DOHA-QATAR",
                "NO",
                false,
                false,
                "raw",
                List.of(new PermitReviewFlightSnapshot(
                        "CAR",
                        1L,
                        BigDecimal.ONE,
                        "QR8364",
                        null,
                        "-2-----",
                        "OTHH",
                        "VVTS",
                        "0100",
                        "0850",
                        "R468",
                        LocalDate.of(2026, 8, 4),
                        LocalDate.of(2026, 8, 4),
                        null,
                        "77X")));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("vatm.aerosync.common.entity")
    @EnableJpaRepositories("vatm.aerosync.common.repository")
    static class TestConfiguration {
    }
}
