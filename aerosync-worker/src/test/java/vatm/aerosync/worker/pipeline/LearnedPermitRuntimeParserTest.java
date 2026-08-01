package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.common.training.PermitTrainingLayoutFingerprinter;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LearnedPermitRuntimeParserTest {

    @Test
    void extractsAnExactActiveLayoutAsReviewRequired() {
        AirportCodeCatalog airports = new AirportCodeCatalog();
        ActiveLearnedPermitProfileCatalog catalog =
                mock(ActiveLearnedPermitProfileCatalog.class);
        LearnedPermitRuntimeParser parser = new LearnedPermitRuntimeParser(
                catalog,
                new LearnedPermitProfileReplayValidator(airports),
                airports);
        WordPermitDocument document = document();
        PermitTrainingDocument captured = captured(document);
        String fingerprint = PermitTrainingLayoutFingerprinter.fingerprint(captured);
        CompiledPermitTrainingProfile compiled = compiled("learned-a", 2);
        when(catalog.activeProfiles()).thenReturn(List.of(
                new ActiveLearnedPermitProfileCatalog.ActiveProfile(
                        7L, fingerprint, compiled)));

        WordPermitParseResult result = parser.tryParse(document, "permit.docx")
                .orElseThrow();

        assertThat(result.profileId()).isEqualTo("learned:learned-a");
        assertThat(result.profileVersion()).isEqualTo(2);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.permit().reviewOnly()).isTrue();
        assertThat(result.permit().normalizedPermitId())
                .isEqualTo("LD 02838/S/CHK/2026");
        assertThat(result.permit().flights()).singleElement()
                .satisfies(flight -> {
                    assertThat(flight.flightNumber()).isEqualTo("QTR8364");
                    assertThat(flight.fromAirport()).isEqualTo("DOH");
                    assertThat(flight.toAirport()).isEqualTo("SGN");
                    assertThat(flight.sourceAircraftType()).isEqualTo("77X");
                });
    }

    @Test
    void quarantinesAmbiguousActiveMatches() {
        AirportCodeCatalog airports = new AirportCodeCatalog();
        ActiveLearnedPermitProfileCatalog catalog =
                mock(ActiveLearnedPermitProfileCatalog.class);
        LearnedPermitRuntimeParser parser = new LearnedPermitRuntimeParser(
                catalog,
                new LearnedPermitProfileReplayValidator(airports),
                airports);
        WordPermitDocument document = document();
        String fingerprint = PermitTrainingLayoutFingerprinter.fingerprint(
                captured(document));
        when(catalog.activeProfiles()).thenReturn(List.of(
                new ActiveLearnedPermitProfileCatalog.ActiveProfile(
                        7L, fingerprint, compiled("learned-a", 1)),
                new ActiveLearnedPermitProfileCatalog.ActiveProfile(
                        8L, fingerprint, compiled("learned-b", 1))));

        assertThatThrownBy(() -> parser.tryParse(document, "permit.docx"))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("More than one active learned format");
    }

    private CompiledPermitTrainingProfile compiled(String key, int version) {
        List<CompiledPermitTrainingProfile.FieldBinding> fields = List.of(
                cell("permit.sourceNumber", 0, 0, 1,
                        "LD-2838/06/2026VN", "LD-2838/06/2026VN", true),
                cell("permit.date", 0, 0, 3,
                        "31/7/2026", "2026-07-31", true),
                cell("operator.icao", 0, 0, 5,
                        "QTR", "QTR", true));
        Map<String, CompiledPermitTrainingProfile.ColumnBinding> columns = Map.of(
                "flightNumber", column(0, "Flight number"),
                "effectiveFrom", column(1, "Effective from"),
                "effectiveTo", column(2, "Effective to"),
                "serviceDays", column(3, "Days of services"),
                "fromAirport", column(4, "Departure Airport"),
                "etd", column(5, "ETD"),
                "toAirport", column(6, "Arrival Airport"),
                "eta", column(7, "ETA"),
                "aircraftType", column(8, "Aircraft Type"));
        return new CompiledPermitTrainingProfile(
                1, key, version, "checksum", "Qatar", "caav-english",
                null, null, fields,
                List.of(new CompiledPermitTrainingProfile.TableBinding(
                        PermitTrainingProfileDefinition.TableRole.SCHEDULE,
                        1, 1, columns)),
                new PermitTrainingProfileDefinition.Options(
                        "CHK", "LD", "A", "S", 24, "NO",
                        true, true, true));
    }

    private CompiledPermitTrainingProfile.FieldBinding cell(
            String semantic,
            int table,
            int row,
            int column,
            String sample,
            String confirmed,
            boolean required) {
        return new CompiledPermitTrainingProfile.FieldBinding(
                semantic, PermitTrainingProfileDefinition.SourceKind.CELL,
                new CompiledPermitTrainingProfile.CellLocator(
                        table, row, column, sample),
                null, null, confirmed, required);
    }

    private CompiledPermitTrainingProfile.ColumnBinding column(
            int index,
            String header) {
        return new CompiledPermitTrainingProfile.ColumnBinding(index, header);
    }

    private WordPermitDocument document() {
        List<List<List<String>>> tables = List.of(
                List.of(List.of(
                        "Permit No.", "LD-2838/06/2026VN",
                        "Permit date", "31/7/2026",
                        "ICAO code", "QTR")),
                List.of(
                        List.of("Flight number", "Effective from", "Effective to",
                                "Days of services", "Departure Airport", "ETD",
                                "Arrival Airport", "ETA", "Aircraft Type"),
                        List.of("QR8364", "04AUG26", "04AUG26", "-2-----",
                                "DOH", "0100", "SGN", "0850", "77X")));
        return new WordPermitDocument(
                "Permit No.: LD-2838/06/2026VN\n1. Carrier/Operator\n2. Schedules (UTC Time)",
                "", "Permit No.: LD-2838/06/2026VN\nICAO code: QTR",
                tables, List.of("Permit details", "Schedules"));
    }

    private PermitTrainingDocument captured(WordPermitDocument source) {
        List<PermitTrainingDocument.Table> tables = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < source.tables().size(); tableIndex++) {
            List<PermitTrainingDocument.Row> rows = new ArrayList<>();
            for (int rowIndex = 0;
                 rowIndex < source.tables().get(tableIndex).size(); rowIndex++) {
                List<PermitTrainingDocument.Cell> cells = new ArrayList<>();
                for (int column = 0;
                     column < source.tables().get(tableIndex).get(rowIndex).size(); column++) {
                    cells.add(new PermitTrainingDocument.Cell(
                            "table-%d-row-%d-cell-%d".formatted(
                                    tableIndex, rowIndex, column),
                            rowIndex, column,
                            source.tables().get(tableIndex).get(rowIndex).get(column)));
                }
                rows.add(new PermitTrainingDocument.Row(rowIndex, cells));
            }
            tables.add(new PermitTrainingDocument.Table(
                    tableIndex, source.tableContexts().get(tableIndex), rows));
        }
        return new PermitTrainingDocument(
                source.paragraphText(), source.tableText(), source.rawContent(),
                tables, source.authoredDate());
    }
}
