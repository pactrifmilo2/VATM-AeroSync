package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CaavGenericOverflightAircraftTableTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void readsAircraftTableWithIcaoAndRegistrationLabels() throws Exception {
        Path file = tempDir.resolve("OF-6047.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HA NOI, 25 AUG 2026");
            paragraph(document, "PERMIT NUMBER: OF-6047/8/2026VN");
            paragraph(document, "1. Carrier/Operator Name: PPG INDUSTRIES");
            paragraph(document, "3. Schedules: UTC Time");
            table(document,
                    new String[] {"Flight number", "Effective from", "Effective to", "Days of services",
                            "Departure Airport", "EOBT", "Arrival Airport", "EIBT"},
                    new String[][] {{"N857GA", "27AUG26", "27AUG26", "---4---",
                            "VABB", "1815", "RJCC", "0325+1"}});
            paragraph(document, "5. Aircraft:");
            table(document,
                    new String[] {"Aircraft Type (ICAO code)", "Aircraft registration mark"},
                    new String[][] {{"G550", "N857GA"}});
            paragraph(document, "6. Airways:");
            table(document, new String[] {"Sector", "Airways"},
                    new String[][] {{"VABB-RJCC", "R474"}});
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.sourceAircraftType()).isEqualTo("G550");
            assertThat(flight.registration()).isEqualTo("N857GA");
        });
    }

    @Test
    void keepsAirwaysPerSectorAndChoosesTheCompleteNonDuplicateLine() throws Exception {
        Path file = tempDir.resolve("OF-6048-multisector.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HA NOI, 25 AUG 2026");
            paragraph(document, "PERMIT NUMBER: OF-6048/8/2026VN");
            paragraph(document, "1. Carrier/Operator Name: NOEVIR AVIATION");
            paragraph(document, "3. Schedules: UTC Time");
            table(document,
                    new String[] {"Flight number", "Effective from", "Effective to", "Days of services",
                            "Departure Airport", "EOBT", "Arrival Airport", "EIBT"},
                    new String[][] {
                            {"N883SJ", "02SEP26", "02SEP26", "--3----",
                                    "SZB", "2230", "DAD", "0000"},
                            {"N883SJ", "02SEP26", "02SEP26", "--3----",
                                    "DAD", "0100", "XSP", "0500+1"}
                    });
            paragraph(document, "5. Aircraft:");
            table(document,
                    new String[] {"Aircraft Type", "Aircraft registration mark"},
                    new String[][] {{"GLF 5", "N883SJ"}});
            paragraph(document, "6. Airways:");
            table(document, new String[] {"Sector", "Airways"},
                    new String[][] {
                            {"SZB-DAD", "M765-L637-Q2-A1"},
                            {"DAD-XSP", "Q1-L644-L642; Q1-W1-L644-L642"}
                    });
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.flights()).extracting(flight -> flight.via())
                .containsExactly("M765/L637/Q2/A1", "Q1/W1/L644/L642");
    }

    @Test
    void readsAircraftAndRouteTablesWithCommonOcrHeaderErrors() throws Exception {
        Path file = tempDir.resolve("OF-6049-ocr-headers.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HA NOI, 25 AUG 2026");
            paragraph(document, "PERMIT NUMBER: OF-6049/8/2026VN");
            paragraph(document, "1. Carrier/Operator Name: PPG INDUSTRIES");
            paragraph(document, "3. Schedules: UTC Time");
            table(document,
                    new String[] {"Flight number", "Effective from", "Effective to", "Days of services",
                            "Departure Airport", "EOBT", "Arrival Airport", "EIBT"},
                    new String[][] {{"N857GA", "27AUG26", "27AUG26", "---4---",
                            "VABB", "1815", "RJCC", "0325+1"}});
            paragraph(document, "5. Aircraft:");
            table(document,
                    new String[] {"Aircrafl Type", "Aircraft Registralion Mark"},
                    new String[][] {{"G550", "N857GA"}});
            paragraph(document, "6. Airways:");
            table(document, new String[] {"Setor", "Airway"},
                    new String[][] {{"VABB-RJCC", "R474"}});
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.sourceAircraftType()).isEqualTo("G550");
            assertThat(flight.registration()).isEqualTo("N857GA");
            assertThat(flight.via()).isEqualTo("R474");
        });
    }

    private void paragraph(XWPFDocument document, String value) {
        document.createParagraph().createRun().setText(value);
    }

    private void table(XWPFDocument document, String[] headers, String[][] rows) {
        XWPFTable table = document.createTable(rows.length + 1, headers.length);
        for (int column = 0; column < headers.length; column++) {
            table.getRow(0).getCell(column).setText(headers[column]);
        }
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < headers.length; column++) {
                table.getRow(row + 1).getCell(column).setText(rows[row][column]);
            }
        }
    }
}
