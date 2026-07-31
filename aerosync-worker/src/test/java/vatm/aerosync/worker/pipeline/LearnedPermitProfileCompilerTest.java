package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LearnedPermitProfileCompilerTest {

    private final LearnedPermitProfileCompiler compiler =
            new LearnedPermitProfileCompiler();
    private final LearnedPermitProfileReplayValidator validator =
            new LearnedPermitProfileReplayValidator(new AirportCodeCatalog());

    @Test
    void compilesSafeSelectorsAndReplaysCorrectedEvidence() throws Exception {
        var compiled = compiler.compile(profile(), definition(), document());

        assertThat(compiled.definitionChecksum()).isEqualTo("a".repeat(64));
        assertThat(compiled.fields().getFirst().text().anchorBefore())
                .isEqualTo("Permit No.: ");
        assertThat(compiled.tables().getFirst().columns()
                .get("flightNumber").columnIndex()).isZero();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readValue(
                mapper.writeValueAsString(compiled),
                CompiledPermitTrainingProfile.class)).isEqualTo(compiled);

        var result = validator.validate(
                compiled,
                document(),
                expectedPermit("QR8364"));

        assertThat(result.passed())
                .as(result.errors().toString())
                .isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.fields())
                .containsEntry("permit.sourceNumber", "LD-2838/06/2026VN")
                .containsEntry("permit.date", "2026-07-31")
                .containsEntry("operator.icao", "QTR");
    }

    @Test
    void reportsMappedValueDifferencesWithoutExecutingTheProfile() {
        var result = validator.validate(
                compiler.compile(profile(), definition(), document()),
                document(),
                expectedPermit("QR9999"));

        assertThat(result.passed()).isFalse();
        assertThat(result.errors())
                .anyMatch(error -> error.startsWith(
                        "schedule[0].flightNumber"));
    }

    private PermitTrainingProfileVersion profile() {
        PermitTrainingProfileVersion profile =
                new PermitTrainingProfileVersion();
        profile.setProfileKey("guided-qatar-cargo");
        profile.setProfileVersion(1);
        profile.setDefinitionChecksum("a".repeat(64));
        profile.setBaseProfileId("caav-generic-landing-issued");
        profile.setBaseProfileVersion(1);
        profile.setCreatedBy("operator.one");
        return profile;
    }

    private PermitTrainingProfileDefinition definition() {
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
                        textField(
                                "purpose",
                                "Purpose of flight(s): All-Cargo Extra.",
                                "CAR"),
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
                "Hanoi, 31/7/2026\nPermit No.: LD-2838/06/2026VN\n"
                        + "Purpose of flight(s): All-Cargo Extra.",
                String.join("\n", headers) + "\n" + String.join("\n", values),
                "Hanoi, 31/7/2026\nPermit No.: LD-2838/06/2026VN\n"
                        + "Purpose of flight(s): All-Cargo Extra.\n"
                        + String.join("\n", headers) + "\n"
                        + String.join("\n", values),
                List.of(new PermitTrainingDocument.Table(
                        0,
                        "2. Schedules (UTC Time)",
                        List.of(row(0, headers), row(1, values)))),
                LocalDate.of(2026, 7, 31));
    }

    private PermitTrainingDocument.Row row(
            int rowIndex,
            List<String> values) {
        List<PermitTrainingDocument.Cell> cells = new ArrayList<>();
        for (int column = 0; column < values.size(); column++) {
            cells.add(new PermitTrainingDocument.Cell(
                    "table-0-row-" + rowIndex + "-cell-" + column,
                    rowIndex,
                    column,
                    values.get(column)));
        }
        return new PermitTrainingDocument.Row(rowIndex, cells);
    }

    private PermitReviewSnapshot expectedPermit(String flightNumber) {
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
                        flightNumber,
                        null,
                        "-2-----",
                        "OTHH",
                        "VVTS",
                        "0100",
                        null,
                        null,
                        LocalDate.of(2026, 8, 4),
                        LocalDate.of(2026, 8, 4),
                        null,
                        null)));
    }
}
