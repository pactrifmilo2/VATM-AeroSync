package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaavEnglishOverflightRevisionProfileTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsOnlyTheNewOverflightSchedule() throws Exception {
        assertThatThrownBy(() -> parser.parse(createDocument(), "OF-5277 (REV1).docx"))
                .hasMessageContaining("IATA and ICAO fields")
                .hasMessageContaining("blank or invalid");
    }

    @Test
    void parse_mapsConfiguredRealDocument() {
        String samplePath = System.getProperty("permit.of5277.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.of5277.sample.path to validate the real revision document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured revision document does not exist");

        assertPermit(parser.parse(file, file.getFileName().toString()));
    }

    private void assertPermit(SchedulePermit permit) {
        assertThat(permit.sourcePermitNumber()).isEqualTo("OF-5277/07/2026VN /REV1");
        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 05277/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("5277");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(permit.operatorId()).isEqualTo("VJT");
        assertThat(permit.billingAddress()).contains("P.O BOX: 54698 DUBAI, UAE");
        assertThat(permit.reference()).isNull();
        assertThat(permit.permitType()).isEqualTo("O/F");
        assertThat(permit.version()).isEqualTo("A");
        assertThat(permit.season()).isEqualTo("S");
        assertThat(permit.validHours()).isEqualTo(72);
        assertThat(permit.flightType()).isEqualTo("NO");
        assertThat(permit.iataAirportsAllowed()).isFalse();
        assertThat(permit.emptyAirwaysAllowed()).isFalse();

        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("VJT547");
            assertThat(flight.purposeId()).isEqualTo("CHT");
            assertThat(flight.craftId()).isZero();
            assertThat(flight.mtow()).isNull();
            assertThat(flight.sourceAircraftType()).isEqualTo("CL60");
            assertThat(flight.serviceDays()).isEqualTo("0030000");
            assertThat(flight.fromAirport()).isEqualTo("WSSL");
            assertThat(flight.toAirport()).isEqualTo("RCSS");
            assertThat(flight.etd()).isEqualTo("0350");
            assertThat(flight.eta()).isNull();
            assertThat(flight.via()).isEqualTo("L625");
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 22));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 22));
            assertThat(flight.remark()).isEqualTo("CHT");
        });

    }

    private Path createDocument() throws Exception {
        Path file = tempDir.resolve("OF-5277 (REV1).docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HA NOI, 21 JUL 2026");
            paragraph(document, "Permit NUMBER: OF-5277/07/2026VN /REV1");
            paragraph(document, "1. Operator:");
            table(document,
                    new String[] {"Name: VISTAJET LIMITED", "Name: VISTAJET LIMITED"},
                    new String[][] {
                            {"IATA code:", "ICAO code:"},
                            {"Postal Address: SKYPARKS BUSINESS CENTRE, LUQA LQA 4000, MALTA",
                                    "Postal Address: SKYPARKS BUSINESS CENTRE, LUQA LQA 4000, MALTA"}
                    });
            paragraph(document, "2. Billing address:");
            table(document,
                    new String[] {"Name: Jetex FZE", "Name: Jetex FZE"},
                    new String[][] {
                            {"Postal Address: P.O BOX: 54698 DUBAI, UAE",
                                    "Postal Address: P.O BOX: 54698 DUBAI, UAE"}
                    });
            paragraph(document, "3. Schedules: UTC Time");
            paragraph(document, "3.1. OLD:");
            schedule(document, "1810", "2230");
            paragraph(document, "3.2. NEW:");
            schedule(document, "0350", "0810Z");
            paragraph(document, "OTHER DETAILS REMAIN UNCHANGED");
            paragraph(document, "4. VALIDITY: -3/+72 HOURS WINDOW FOR PSBL DELAY");
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document, String etd, String eta) {
        table(document,
                new String[] {"Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA"},
                new String[][] {{"VJT547", "22JUL26", "22JUL26", "--3----",
                        "WSSL", etd, "RCSS", eta}});
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
