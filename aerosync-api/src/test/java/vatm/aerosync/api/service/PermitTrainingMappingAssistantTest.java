package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermitTrainingMappingAssistantTest {

    private final PermitTrainingMappingAssistant assistant =
            new PermitTrainingMappingAssistant();

    @Test
    void infersUniqueScalarsScheduleRouteAndAircraftWithoutConstants() {
        PermitTrainingMappingAssistant.Assistance result = assistant.suggest(
                document(true), expected("QTR"), "Qatar permit", "caav-english");

        assertThat(result.definition().fields())
                .extracting(PermitTrainingProfileDefinition.FieldMapping::source)
                .doesNotContain(PermitTrainingProfileDefinition.SourceKind.CONSTANT);
        assertThat(result.definition().tables())
                .extracting(PermitTrainingProfileDefinition.TableMapping::role)
                .contains(
                        PermitTrainingProfileDefinition.TableRole.SCHEDULE,
                        PermitTrainingProfileDefinition.TableRole.ROUTE,
                        PermitTrainingProfileDefinition.TableRole.AIRCRAFT);
        assertThat(result.definition().options().reviewOnly()).isTrue();
    }

    @Test
    void leavesMissingOperatorForAnExplicitOperatorDecision() {
        PermitTrainingMappingAssistant.Assistance result = assistant.suggest(
                document(false), expected("POS"), "Private permit", "caav-english");

        assertThat(result.unresolved()).contains("operator.icao");
        assertThat(result.definition().fields())
                .noneMatch(field -> field.semanticField().equals("operator.icao"));
    }

    @Test
    void recognizesVietnameseScheduleHeadersIncludingAircraftType() {
        PermitTrainingDocument document = new PermitTrainingDocument(
                "HÀ NỘI, NGÀY 31/7/2026\nLD-2846/7/2026VN\nMã ICAO: HVN",
                "", "HÀ NỘI, NGÀY 31/7/2026\nLD-2846/7/2026VN\nMã ICAO: HVN",
                List.of(table(0, List.of(
                        List.of("Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                                "Ngày trong tuần", "Sân bay cất cánh",
                                "Giờ dự kiến cất cánh", "Sân bay hạ cánh",
                                "Giờ dự kiến hạ cánh", "Loại tàu bay"),
                        List.of("VN7109", "01AUG26", "01AUG26", "6", "DAD",
                                "23:05", "SGN", "00:25+1", "321/320")))), null);

        PermitTrainingMappingAssistant.Assistance result = assistant.suggest(
                document, expected("HVN"), "Vietnamese permit", "caav-vietnamese");

        PermitTrainingProfileDefinition.TableMapping schedule = result.definition()
                .tables().stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.SCHEDULE)
                .findFirst().orElseThrow();
        assertThat(schedule.columns()).containsKeys(
                "flightNumber", "effectiveFrom", "effectiveTo", "serviceDays",
                "fromAirport", "etd", "toAirport", "eta", "aircraftType");
        assertThat(result.unresolved()).doesNotContain("aircraft.aircraftType");
    }

    private PermitTrainingDocument document(boolean includeOperator) {
        String raw = "Hanoi, 31/7/2026\nPermit No.: LD-2838/06/2026VN\n"
                + (includeOperator ? "ICAO code: QTR\n" : "")
                + "Postal Address: P.O BOX 22550, DOHA-QATAR";
        return new PermitTrainingDocument(
                raw, "", raw,
                List.of(
                        table(0, List.of(
                                List.of("Flight number", "Effective from", "Effective to", "Days of services", "Departure Airport", "ETD", "Arrival Airport", "ETA"),
                                List.of("QR8364", "04AUG26", "04AUG26", "-2-----", "DOH", "0100", "SGN", "0850"))),
                        table(1, List.of(
                                List.of("Aircraft Type", "Registration Mark"),
                                List.of("77X", "As per FAOC of QR"))),
                        table(2, List.of(
                                List.of("Sector", "Airways"),
                                List.of("DOH-SGN", "R468")))) ,
                null);
    }

    private PermitTrainingDocument.Table table(
            int tableIndex,
            List<List<String>> sourceRows) {
        List<PermitTrainingDocument.Row> rows = new java.util.ArrayList<>();
        for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
            List<PermitTrainingDocument.Cell> cells = new java.util.ArrayList<>();
            for (int column = 0; column < sourceRows.get(rowIndex).size(); column++) {
                cells.add(new PermitTrainingDocument.Cell(
                        "table-%d-row-%d-cell-%d".formatted(
                                tableIndex, rowIndex, column),
                        rowIndex, column, sourceRows.get(rowIndex).get(column)));
            }
            rows.add(new PermitTrainingDocument.Row(rowIndex, cells));
        }
        return new PermitTrainingDocument.Table(tableIndex, "", rows);
    }

    private PermitReviewSnapshot expected(String operator) {
        return new PermitReviewSnapshot(
                "LD-2838/06/2026VN", "LD 02838/S/CHK/2026", "2838",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 7, 31),
                operator, null, 24, "P.O BOX 22550, DOHA-QATAR", "NO",
                true, false, "", List.of(new PermitReviewFlightSnapshot(
                "NO", 77L, null, "QTR8364", "As per FAOC of QR", "0200000",
                "DOH", "SGN", "0100", "0850", "R468",
                LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4),
                "NO 77X", "77X")));
    }
}
