package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DocxSchedulePermitParserTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsScheduledOverflightPermit() throws Exception {
        Path file = createPermitDocument();

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("5199");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(permit.operatorId()).isEqualTo("RMY");
        assertThat(permit.authorId()).isEqualTo("CHK");
        assertThat(permit.flightType()).isEqualTo("SC");
        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("RMY685");
            assertThat(flight.serviceDays()).isEqualTo("1000000");
            assertThat(flight.fromAirport()).isEqualTo("WMKK");
            assertThat(flight.toAirport()).isEqualTo("VHHH");
            assertThat(flight.etd()).isEqualTo("1140");
            assertThat(flight.eta()).isNull();
            assertThat(flight.via()).isEqualTo("M765/M771");
            assertThat(flight.craftId()).isZero();
            assertThat(flight.mtow()).isNull();
            assertThat(flight.sourceAircraftType()).isEqualTo("76X/32X");
            assertThat(flight.remark()).isEqualTo("CAR 76X/32X");
        });
    }

    @Test
    void parse_mapsGenericLandingPermitAndReconcilesSingleDayWeekday() throws Exception {
        Path file = createGenericLandingPermitDocument();

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 02483/S/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("VNB");
        assertThat(permit.reviewOnly()).isFalse();
        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("VNB593");
            assertThat(flight.purposeId()).isEqualTo("FER");
            assertThat(flight.craftId()).isZero();
            assertThat(flight.mtow()).isNull();
            assertThat(flight.sourceAircraftType()).isEqualTo("C208");
            assertThat(flight.serviceDays()).isEqualTo("0004000");
            assertThat(flight.fromAirport()).isEqualTo("VVBM");
            assertThat(flight.toAirport()).isEqualTo("VVCI");
            assertThat(flight.via()).isEqualTo("W1/DAN/W2");
        });
    }

    @Test
    void parse_mapsConfiguredRealPermitDocument() {
        String samplePath = System.getProperty("permit.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.sample.path to validate a real permit document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured permit document does not exist");

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("RMY");
        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("RMY685");
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 20));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 27));
            assertThat(flight.fromAirport()).isEqualTo("WMKK");
            assertThat(flight.toAirport()).isEqualTo("VHHH");
            assertThat(flight.etd()).isEqualTo("1140");
            assertThat(flight.via()).isEqualTo("M765/M771");
        });
    }

    private Path createPermitDocument() throws Exception {
        Path file = tempDir.resolve("OF-5199.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("HANOI, 17-Jul-26");
            document.createParagraph().createRun().setText("PERMIT NUMBER OF-5199/7/2026VN");
            document.createParagraph().createRun().setText("2. Billing address:");
            document.createParagraph().createRun().setText("3. Schedules: UTC Time");
            document.createParagraph().createRun().setText("4. Purpose of flight(s): Cargo flight");
            document.createParagraph().createRun().setText("(Ref. G17.44-260715-170787)");

            XWPFTable operator = document.createTable(2, 2);
            operator.getRow(0).getCell(0).setText("Name: RAYA AIRWAYS");
            operator.getRow(0).getCell(1).setText("ICAO Code: RMY");
            operator.getRow(1).getCell(0).setText("Postal address: Cyberjaya, Malaysia");

            XWPFTable schedule = document.createTable(2, 8);
            String[] headers = {"Flight number", "Eff from", "Eff to", "Day(s) of services",
                    "Dep airport", "ETD", "Arr airport", "ETA"};
            String[] values = {"RMY685", "20JUL26", "27JUL26", "1------",
                    "WMKK", "1140", "VHHH", "1550"};
            for (int index = 0; index < headers.length; index++) {
                schedule.getRow(0).getCell(index).setText(headers[index]);
                schedule.getRow(1).getCell(index).setText(values[index]);
            }

            XWPFTable route = document.createTable(2, 4);
            route.getRow(0).getCell(0).setText("Sector");
            route.getRow(0).getCell(1).setText("Airways");
            route.getRow(0).getCell(2).setText("Entry point into Vietnam FIR");
            route.getRow(0).getCell(3).setText("Exit point from Vietnam FIR");
            route.getRow(1).getCell(0).setText("WMKK - VHHH");
            route.getRow(1).getCell(1).setText("M765-M771");
            route.getRow(1).getCell(2).setText("IGARI");
            route.getRow(1).getCell(3).setText("DONDA");

            XWPFTable aircraft = document.createTable(4, 4);
            aircraft.getRow(0).getCell(0).setText("Aircraft type");
            aircraft.getRow(0).getCell(1).setText("Registration marks");
            aircraft.getRow(0).getCell(2).setText("Mtow (T)");
            aircraft.getRow(0).getCell(3).setText("Nationality");
            aircraft.getRow(1).getCell(0).setText("76X");
            aircraft.getRow(2).getCell(0).setText("76X");
            aircraft.getRow(3).getCell(0).setText("32X");

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private Path createGenericLandingPermitDocument() throws Exception {
        Path file = tempDir.resolve("LD-2483.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun()
                    .setText("LANDING PERMIT FOR NON-SCHEDULED FLIGHT");
            document.createParagraph().createRun().setText("Hanoi, 01/07/2026");
            document.createParagraph().createRun().setText("Permit No.: LD-2483/07/2026VN");
            document.createParagraph().createRun().setText("3. Purpose of Flight(s): FERRY");

            XWPFTable schedule = document.createTable(2, 9);
            String[] headers = {"Flight number", "Effective from", "Effective to",
                    "Days of services", "Departure Airport", "ETD", "Arrival Airport",
                    "ETA", "Aircraft Type"};
            String[] values = {"VN-B593", "02JUL26", "02JUL26", "-2-----",
                    "BMV", "0000", "HPH", "0400", "C208"};
            for (int index = 0; index < headers.length; index++) {
                schedule.getRow(0).getCell(index).setText(headers[index]);
                schedule.getRow(1).getCell(index).setText(values[index]);
            }

            XWPFTable route = document.createTable(2, 2);
            route.getRow(0).getCell(0).setText("Sector");
            route.getRow(0).getCell(1).setText("Airways");
            route.getRow(1).getCell(0).setText("BMV-HPH");
            route.getRow(1).getCell(1).setText("W1 - DAN - W2");

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }
}
