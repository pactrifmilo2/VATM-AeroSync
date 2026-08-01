package vatm.aerosync.common.training;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.PermitTrainingDocument;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermitTrainingLayoutFingerprinterTest {

    @Test
    void ignoresPermitValuesAndScheduleRowCount() {
        PermitTrainingDocument first = document(
                "Permit No.: LD-2838/06/2026VN",
                List.of(
                        List.of("Flight number", "Departure Airport", "Arrival Airport"),
                        List.of("QR8364", "DOH", "SGN")));
        PermitTrainingDocument second = document(
                "Permit No.: LD-9911/09/2027VN",
                List.of(
                        List.of("Flight number", "Departure Airport", "Arrival Airport"),
                        List.of("QR9001", "LHR", "HAN"),
                        List.of("QR9002", "HAN", "LHR")));

        assertThat(PermitTrainingLayoutFingerprinter.fingerprint(first))
                .isEqualTo(PermitTrainingLayoutFingerprinter.fingerprint(second));
    }

    @Test
    void changesWhenHeaderStructureChanges() {
        PermitTrainingDocument first = document(
                "Permit No.: LD-2838/06/2026VN",
                List.of(List.of("Flight number", "Departure Airport", "Arrival Airport")));
        PermitTrainingDocument second = document(
                "Permit No.: LD-9911/09/2027VN",
                List.of(List.of("Flight number", "Departure Airport", "Airways")));

        assertThat(PermitTrainingLayoutFingerprinter.fingerprint(first))
                .isNotEqualTo(PermitTrainingLayoutFingerprinter.fingerprint(second));
    }

    private PermitTrainingDocument document(
            String permitLine,
            List<List<String>> sourceRows) {
        List<PermitTrainingDocument.Row> rows = new java.util.ArrayList<>();
        for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
            List<PermitTrainingDocument.Cell> cells = new java.util.ArrayList<>();
            for (int column = 0; column < sourceRows.get(rowIndex).size(); column++) {
                cells.add(new PermitTrainingDocument.Cell(
                        "table-0-row-%d-cell-%d".formatted(rowIndex, column),
                        rowIndex, column, sourceRows.get(rowIndex).get(column)));
            }
            rows.add(new PermitTrainingDocument.Row(rowIndex, cells));
        }
        return new PermitTrainingDocument(
                permitLine + "\n1. Carrier/Operator\n2. Schedules (UTC Time)",
                "", permitLine,
                List.of(new PermitTrainingDocument.Table(0, "Schedules", rows)),
                null);
    }
}
