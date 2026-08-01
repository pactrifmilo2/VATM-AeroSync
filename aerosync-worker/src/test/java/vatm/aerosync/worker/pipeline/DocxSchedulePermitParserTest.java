package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

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
    void parse_genericLandingWithOriginalAndNewSchedules_prefersNewSchedule() throws Exception {
        Path file = createGenericLandingReplacementScheduleDocument();

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 01471/S/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("HVN");
        assertThat(permit.flights())
                .extracting(flight -> flight.flightNumber())
                .containsExactly("HVN200", "HVN201", "HVN202", "HVN300");
        assertThat(permit.flights())
                .extracting(flight -> flight.etd())
                .containsExactly("0905", "1105", "1205", "1300");
        assertThat(permit.flights())
                .allSatisfy(flight -> {
                    assertThat(flight.sourceAircraftType()).isEqualTo("321/320");
                    assertThat(flight.craftId()).isZero();
                    assertThat(flight.mtow()).isNull();
                });
    }

    @Test
    void parse_vjcWithoutIcao_readsInternationalAndDomesticTablesAndUsesCurrentDate()
            throws Exception {
        Path file = createVjcPermitWithoutIcao();
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-07-31T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        DocxSchedulePermitParser fixedClockParser = new DocxSchedulePermitParser(
                new WordPermitDocumentReader(),
                new WordPermitFormatDetector(new DocxPermitProfileCatalog()),
                new AirportCodeCatalog(),
                new PermitOperatorCatalog(),
                fixedClock);

        SchedulePermit permit = fixedClockParser.parse(file, file.getFileName().toString());

        assertThat(permit.operatorId()).isEqualTo("VJC");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(permit.flights())
                .extracting(flight -> flight.flightNumber())
                .containsExactly("VJC8890", "VJC1282");
    }

    @Test
    void parse_withoutIcao_passesIataAndCarrierNameToAtfmResolver() throws Exception {
        Path file = createVjcPermitWithoutIcao();
        String[] resolvedInput = new String[2];
        PermitOperatorResolver resolver = (iataCode, carrierName) -> {
            resolvedInput[0] = iataCode;
            resolvedInput[1] = carrierName;
            return Optional.of("VJC");
        };
        DocxSchedulePermitParser atfmBackedParser = new DocxSchedulePermitParser(
                new WordPermitDocumentReader(),
                new WordPermitFormatDetector(new DocxPermitProfileCatalog()),
                new AirportCodeCatalog(),
                new PermitOperatorCatalog(),
                resolver,
                Clock.systemDefaultZone());

        SchedulePermit permit = atfmBackedParser.parse(file, file.getFileName().toString());

        assertThat(resolvedInput).containsExactly(
                "VJ", "Công ty cổ phần hàng không Vietjet (Vietjet air)");
        assertThat(permit.operatorId()).isEqualTo("VJC");
        assertThat(permit.flights()).extracting(flight -> flight.flightNumber())
                .containsExactly("VJC8890", "VJC1282");
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

    @Test
    void parseAndNormalize_mapsConfiguredHvnPermitDocument() {
        String samplePath = System.getProperty("permit.hvn.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.hvn.sample.path to validate the HVN permit document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured HVN permit document does not exist");

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, file.toString(), "h", FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        new NormalizerStep(ZoneId.of("UTC")).normalize(context);

        assertThat(permit.operatorId()).isEqualTo("HVN");
        assertThat(context.getSchedulePermit().flights())
                .extracting(flight -> flight.flightNumber())
                .containsExactly("HVN1822", "HVN7158", "HVN7056", "HVN7058", "HVN7060");
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

    private Path createGenericLandingReplacementScheduleDocument() throws Exception {
        Path file = tempDir.resolve("LD-2517.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("HANOI, 03/7/2026");
            document.createParagraph().createRun().setText("LD-1471/7/2026VN");

            XWPFTable operator = document.createTable(1, 3);
            operator.getRow(0).getCell(0).setText("Name: VIETNAM AIRLINES");
            operator.getRow(0).getCell(1).setText("Mã IATA (nếu có): VN");
            operator.getRow(0).getCell(2).setText("Mã ICAO (nếu có): HVN");

            document.createParagraph().createRun().setText("2.1. ORIGINAL SCHEDULE");
            scheduleTable(document, new String[][] {
                    {"VN100", "04JUL26", "04JUL26", "6", "SGN", "1000",
                            "VCL", "1125", "321/320"}
            });

            document.createParagraph().createRun().setText("2.2. NEW SCHEDULE");
            scheduleTable(document, new String[][] {
                    {"VN200", "04JUL26", "04JUL26", "6", "SGN", "0905",
                            "VCL", "1030", "321/320"},
                    {"VN201", "04JUL26", "04JUL26", "6", "VCL", "1105",
                            "SGN", "1230", "321/320"}
            });

            document.createParagraph().createRun().setText("2.3. DOMESTIC SCHEDULE");
            scheduleTable(document, new String[][] {
                    {"VN202", "04JUL26", "04JUL26", "6", "HAN", "1205",
                            "DAD", "1325", "321/320"}
            });

            document.createParagraph().createRun().setText("2.5. TRANSFER FLIGHTS");
            scheduleTable(document, new String[][] {
                    {"VN300", "04JUL26", "04JUL26", "6", "HAN", "1300",
                            "DAD", "1425", "321/320"}
            });

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private Path createVjcPermitWithoutIcao() throws Exception {
        Path file = tempDir.resolve("LD-2821-VJC-no-ICAO.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("LD-2821/7/2026VN");
            document.createParagraph().createRun().setText("Mục đích: Chở khách");

            XWPFTable operator = document.createTable(3, 2);
            operator.getRow(0).getCell(0).setText(
                    "Tên: Công ty cổ phần hàng không Vietjet (Vietjet air)");
            operator.getRow(1).getCell(0).setText("Mã IATA: VJ");
            operator.getRow(1).getCell(1).setText("Mã ICAO:");
            operator.getRow(2).getCell(0).setText("Địa chỉ bưu điện: Hà Nội");

            document.createParagraph().createRun().setText("2.1. Lịch bay quốc tế");
            vjcScheduleTable(document, new String[][] {
                    {"VJ8890", "04-Aug-26", "04-Aug-26", "2", "DAD", "5:15", "HAN", "6:50"}
            });
            document.createParagraph().createRun().setText("2.2. Lịch bay quốc nội");
            vjcScheduleTable(document, new String[][] {
                    {"VJ1282", "05-Aug-26", "05-Aug-26", "3", "HAN", "7:15", "SGN", "9:20"}
            });

            XWPFTable aircraft = document.createTable(2, 2);
            aircraft.getRow(0).getCell(0).setText("Loại tàu bay");
            aircraft.getRow(0).getCell(1).setText("Số đăng ký");
            aircraft.getRow(1).getCell(0).setText("320");

            XWPFTable route = document.createTable(3, 2);
            route.getRow(0).getCell(0).setText("Chặng bay");
            route.getRow(0).getCell(1).setText("Đường hàng không");
            route.getRow(1).getCell(0).setText("DAD-HAN");
            route.getRow(1).getCell(1).setText("W1");
            route.getRow(2).getCell(0).setText("HAN-SGN");
            route.getRow(2).getCell(1).setText("W2");

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void vjcScheduleTable(XWPFDocument document, String[][] rows) {
        String[] headers = {"Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                "Ngày trong tuần", "Sân bay cất cánh", "Giờ khởi hành dự kiến",
                "Sân bay hạ cánh", "Giờ hạ cánh dự kiến"};
        XWPFTable schedule = document.createTable(rows.length + 1, headers.length);
        for (int column = 0; column < headers.length; column++) {
            schedule.getRow(0).getCell(column).setText(headers[column]);
        }
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < headers.length; column++) {
                schedule.getRow(row + 1).getCell(column).setText(rows[row][column]);
            }
        }
    }

    private void scheduleTable(XWPFDocument document, String[][] rows) {
        String[] headers = {"Flight number", "Effective from", "Effective to",
                "Days of services", "Departure Airport", "ETD", "Arrival Airport",
                "ETA", "Aircraft Type"};
        XWPFTable schedule = document.createTable(rows.length + 1, headers.length);
        for (int column = 0; column < headers.length; column++) {
            schedule.getRow(0).getCell(column).setText(headers[column]);
        }
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < headers.length; column++) {
                schedule.getRow(row + 1).getCell(column).setText(rows[row][column]);
            }
        }
    }
}
