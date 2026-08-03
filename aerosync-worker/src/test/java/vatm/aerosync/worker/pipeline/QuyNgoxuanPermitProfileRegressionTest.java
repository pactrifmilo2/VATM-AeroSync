package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuyNgoxuanPermitProfileRegressionTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void karRevision_readsMonthStylePermitNumberAndOnlyNewSchedule() {
        LegacyDocRevisionPermitParser legacyParser = new LegacyDocRevisionPermitParser(parser);
        String raw = """
                Revision of landing/overflight permit
                Hanoi, 01/08/2026
                Permit No.: LD-2785/07/2026VN/REV1
                Name: LLC 'IKAR' IATA code: EO ICAO code: KAR
                Postal Address: Sheremetyevo Airport, P.O. Box 172
                2.1. Original schedule(s) (UTC Time)
                2.2. New schedule(s) (UTC Time)
                3. Purpose of Flight: Charter PAX flights
                """;
        List<List<List<String>>> tables = List.of(
                List.of(
                        List.of("Name: LLC 'IKAR'"),
                        List.of("IATA code: EO", "ICAO code: KAR"),
                        List.of("Postal Address: Sheremetyevo Airport, P.O. Box 172")),
                schedule(true, "EO3657", "1230", "2005", "B772/B739"),
                schedule(false, "EO3680", "1035", "1810", "B772"),
                List.of(
                        List.of("Aircraft Type", "Registration Mark", "Nationality"),
                        List.of("B772", "RA-73272", "Russia")),
                List.of(
                        List.of("Sector", "Airways", "Entry Point into Vietnam FIR", "Exit Point from Vietnam FIR"),
                        List.of("CXR-OVB", "VVCR KARAN W2 DAN", "", "KATBO")));

        SchedulePermit permit = legacyParser.parseContent(raw, tables, "LD 2785 (REV1).doc");

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 02785/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("2785");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(permit.operatorId()).isEqualTo("KAR");
        assertThat(permit.reviewOnly()).isTrue();
        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("KAR3680");
            assertThat(flight.fromAirport()).isEqualTo("CXR");
            assertThat(flight.toAirport()).isEqualTo("OVB");
            assertThat(flight.etd()).isEqualTo("1035");
            assertThat(flight.eta()).isEqualTo("1810");
            assertThat(flight.sourceAircraftType()).isEqualTo("B772");
            assertThat(flight.purposeId()).isEqualTo("PAX");
        });
    }

    @Test
    void vfcIssued_readsPermitNumberWithoutHyphen() throws Exception {
        Path file = tempDir.resolve("LD 2856 VFC8068-.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HÀ NỘI, NGÀY 01/8/2026");
            paragraph(document, "LD2856/8/2026VN");
            table(document,
                    new String[] {"Hãng hàng không", "Mã"},
                    new String[][] {{"Mã IATA: 0V", "Mã ICAO: VFC"}});
            paragraph(document, "Lịch bay (Giờ UTC)");
            table(document,
                    new String[] {"Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                            "Ngày trong tuần", "Sân bay cất cánh", "Giờ khởi hành dự kiến",
                            "Sân bay hạ cánh", "Giờ dựkiến hạcánh", "Loại tàu bay"},
                    new String[][] {{"0V8068", "02AUG26", "30AUG26", "7", "VCS",
                            "09:45", "SGN", "10:50", "ATR72"}});
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 02856/S/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("VFC");
        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("VFC8068");
            assertThat(flight.fromAirport()).isEqualTo("VVCS");
            assertThat(flight.toAirport()).isEqualTo("VVTS");
            assertThat(flight.etd()).isEqualTo("0945");
            assertThat(flight.eta()).isEqualTo("1050");
        });
    }

    @Test
    void fdxOverflight_repairsDuplicateScheduleAirportFromPublishedSector() throws Exception {
        Path file = tempDir.resolve("OF 5517-FDX9082.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HA NOI, 01 AUG 2026");
            paragraph(document, "PERMIT NUMBER: OF-5517/08/2026VN");
            table(document,
                    new String[] {"Carrier", "Codes"},
                    new String[][] {{"Name: Federal Express Corporation",
                            "IATA code: JD   ICAO code: FDX"}});
            paragraph(document, "3. Schedules: UTC Time");
            table(document,
                    new String[] {"Flight number", "Effective from", "Effective to",
                            "Days of services", "Departure Airport", "ETD",
                            "Arrival Airport", "ETA"},
                    new String[][] {{"FDX9082", "16AUG26", "16AUG26", "0000007",
                            "WSSS", "0300", "WSSS", "1540"}});
            paragraph(document, "4. Purpose of Flight(s): Cargo");
            paragraph(document, "5. Aircraft:");
            table(document,
                    new String[] {"Aircraft Type", "Registration Mark"},
                    new String[][] {{"MD11", "As per AOC"}, {"B77F", ""}, {"B763", ""}});
            paragraph(document, "6. Airways");
            table(document,
                    new String[] {"Sector", "Airways"},
                    new String[][] {{"WSSS-PANC", "M771-M765-L625"}});
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 05517/S/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("FDX");
        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("FDX9082");
            assertThat(flight.fromAirport()).isEqualTo("WSSS");
            assertThat(flight.toAirport()).isEqualTo("PANC");
            assertThat(flight.via()).isEqualTo("M771/M765/L625");
            assertThat(flight.sourceAircraftType()).isEqualTo("MD11/B77F/B763");
            assertThat(flight.purposeId()).isEqualTo("CAR");
        });
    }

    private List<List<String>> schedule(boolean original,
                                        String flight,
                                        String etd,
                                        String eta,
                                        String aircraft) {
        List<String> header = original
                ? List.of("Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA", "Aircraft Type",
                        "Original permit")
                : List.of("Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA", "Aircraft Type");
        List<String> row = original
                ? List.of(flight, "02AUG26", "02AUG26", "------7", "CXR", etd, "OVB", eta,
                        aircraft, "LD-2785/07/2026VN")
                : List.of(flight, "02AUG26", "02AUG26", "------7", "CXR", etd, "OVB", eta,
                        aircraft);
        return List.of(header, row);
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
