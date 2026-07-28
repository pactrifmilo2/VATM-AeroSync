package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CaavEnglishIssuedPermitRevisionProfileTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsOnlyTheNewJejuAirSchedule() throws Exception {
        assertPermit(parser.parse(createDocument(), "LD-83_A_S_2026VN.RVS.22JUL26.docx"));
    }

    @Test
    void parse_mapsConfiguredRealDocument() {
        String samplePath = System.getProperty("permit.ld83.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.ld83.sample.path to validate the real LD-83 document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured LD-83 document does not exist");

        assertPermit(parser.parse(file, file.getFileName().toString()));
    }

    private void assertPermit(SchedulePermit permit) {
        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-83/A/S/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 0083A/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("83A");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(permit.operatorId()).isEqualTo("JJA");
        assertThat(permit.reference()).isEqualTo("LD - 83/A/S/2026VN");
        assertThat(permit.permitType()).isEqualTo("LD");
        assertThat(permit.version()).isEqualTo("A");
        assertThat(permit.season()).isEqualTo("S");
        assertThat(permit.validHours()).isEqualTo(24);
        assertThat(permit.flightType()).isEqualTo("SC");
        assertThat(permit.iataAirportsAllowed()).isTrue();
        assertThat(permit.emptyAirwaysAllowed()).isTrue();
        assertThat(permit.billingAddress()).contains("AVIATION SERVICE CENTER");

        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("7C2396");
            assertThat(flight.purposeId()).isEqualTo("PAX");
            assertThat(flight.craftId()).isEqualTo(249L);
            assertThat(flight.mtow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(flight.serviceDays()).isEqualTo("0200000");
            assertThat(flight.fromAirport()).isEqualTo("PQC");
            assertThat(flight.toAirport()).isEqualTo("ICN");
            assertThat(flight.etd()).isEqualTo("1525");
            assertThat(flight.eta()).isEqualTo("2105");
            assertThat(flight.via()).isNull();
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 21));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 21));
            assertThat(flight.remark()).isEqualTo("PAX 738/7M8");
        });

        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "LD-83_A_S_2026VN.RVS.22JUL26.docx", "ld83", FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        assertDoesNotThrow(() -> new BusinessRuleValidatorStep().validate(context));
    }

    private Path createDocument() throws Exception {
        Path file = tempDir.resolve("LD-83_A_S_2026VN.RVS.22JUL26.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "Hanoi, 22/7/2026");
            paragraph(document, "Refer to the issued permit: LD - 83/A/S/2026VN");
            paragraph(document, "1. Carrier/Operator");
            table(document,
                    new String[] {"Carrier details"},
                    new String[][] {
                            {"Name: JJA(JEJUAIR)/7C(JEJUAIR) /JEJUAIR OPERATIONS CONTROL CENTER"},
                            {"IATA code: 7C     ICAO code: JJA"},
                            {"Postal Address: AVIATION SERVICE CENTER, JEJU INTERNATIONAL AIRPORT"}
                    });
            paragraph(document, "2. Schedules");
            paragraph(document, "2.1. Original schedule(s) (UTC Time)");
            schedule(document, true, "7C2316", "0200000");
            paragraph(document, "2.2. New schedule(s) (UTC Time)");
            schedule(document, false, "7C2396", "0020000");
            paragraph(document, "3. Note: Other details unchanged.");
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document,
                          boolean original,
                          String flightNumber,
                          String serviceDays) {
        String[] headers = original
                ? new String[] {"Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA", "Aircraft Type",
                        "Original permit(3)"}
                : new String[] {"Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA", "Aircraft Type"};
        String[][] rows = original
                ? new String[][] {{flightNumber, "21JUL26", "21JUL26", serviceDays,
                        "PQC", "1525", "ICN", "2105", "738/7M8", "LD - 83/A/S/2026VN"}}
                : new String[][] {{flightNumber, "21JUL26", "21JUL26", serviceDays,
                        "PQC", "1525", "ICN", "2105", "738/7M8"}};
        table(document, headers, rows);
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
