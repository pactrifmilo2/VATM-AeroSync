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
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DocxSchedulePermitParserTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsScheduledOverflightPermit() throws Exception {
        Path file = createPermitDocument();

        WordPermitParseResult parseResult =
                parser.parseWithDiagnostics(file, file.getFileName().toString());
        SchedulePermit permit = parseResult.permit();

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

        WordPermitParseResult genericParseResult =
                parser.parseWithDiagnostics(file, file.getFileName().toString());
        SchedulePermit permit = genericParseResult.permit();

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 02483/S/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("VNB");
        assertThat(permit.reviewOnly())
                .as(genericParseResult.warnings().toString())
                .isFalse();
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
                .containsExactly("HVN200", "HVN201", "HVN300");
        assertThat(permit.flights())
                .extracting(flight -> flight.etd())
                .containsExactly("0905", "1105", "1300");
        assertThat(permit.flights())
                .allSatisfy(flight -> {
                    assertThat(flight.sourceAircraftType()).isEqualTo("321/320");
                    assertThat(flight.craftId()).isZero();
                    assertThat(flight.mtow()).isNull();
                });
    }

    @Test
    void parse_sharedSemanticsHandleVietnameseVariantWithoutNewProfile() throws Exception {
        Path file = createVietnameseSemanticVariantDocument();

        WordPermitParseResult parseResult =
                parser.parseWithDiagnostics(file, file.getFileName().toString());
        SchedulePermit permit = parseResult.permit();

        assertThat(parseResult.profileId())
                .isEqualTo("spa066-vietnamese-landing-revision");
        assertThat(permit.sourcePermitNumber()).isEqualTo("LD- 11112/7/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD-11112/07/2026");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(permit.operatorId()).isEqualTo("HVN");
        assertThat(permit.billingAddress()).isEqualTo("200 Nguyen Son, Ha Noi");
        assertThat(permit.flights())
                .extracting(flight -> flight.flightNumber())
                .containsExactly("VN1466", "VN7180");
        assertThat(permit.flights())
                .extracting(flight -> flight.etd())
                .containsExactly("0905", "1715");
        assertThat(permit.reviewOnly()).isTrue();
        assertThat(parseResult.fields())
                .anySatisfy(field -> {
                    assertThat(field.field()).isEqualTo("permitNumber");
                    assertThat(field.method()).isEqualTo("SEMANTIC_HEADER");
                })
                .anySatisfy(field -> {
                    assertThat(field.field()).isEqualTo("permitDate");
                    assertThat(field.method()).isEqualTo("DATE_NEAR_LABEL");
                })
                .anySatisfy(field -> {
                    assertThat(field.field()).isEqualTo("schedule.tableRole");
                    assertThat(field.method()).isEqualTo("SEMANTIC_REPLACEMENT");
                })
                .anySatisfy(field -> {
                    assertThat(field.field()).isEqualTo("schedule.tableRole");
                    assertThat(field.method()).isEqualTo("SEMANTIC_SUPPLEMENTAL");
                });
        assertThat(parseResult.warnings())
                .anyMatch(warning -> warning.code().equals("SEMANTIC_TABLE_ROLE"));
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

    @Test
    void parse_mapsConfiguredSemanticVariantDocument() {
        String samplePath = System.getProperty("permit.semantic.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.semantic.sample.path to validate a shared-semantic permit");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file),
                "Configured shared-semantic permit document does not exist");

        WordPermitParseResult parseResult =
                parser.parseWithDiagnostics(file, file.getFileName().toString());
        SchedulePermit permit = parseResult.permit();

        assertThat(parseResult.profileId())
                .isEqualTo("spa066-vietnamese-landing-revision");
        assertThat(permit.sourcePermitNumber())
                .matches("LD-\\s*\\d{1,5}/7/2026VN");
        assertThat(permit.normalizedPermitId())
                .isEqualTo("LD-" + permit.permitNumber() + "/07/2026");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(permit.operatorId()).isEqualTo("HVN");
        assertThat(permit.billingAddress()).contains("200");
        assertThat(permit.flights()).hasSize(17);
        assertThat(permit.flights())
                .extracting(flight -> flight.flightNumber())
                .allSatisfy(flightNumber -> assertThat(flightNumber).startsWith("VN"));
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

    private Path createVietnameseSemanticVariantDocument() throws Exception {
        Path file = tempDir.resolve("LD-semantic-variant.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun()
                    .setText("H\u00c0 N\u1ed8I, NG\u00c0Y 03/7/2026");
            document.createParagraph().createRun().setText("LD- 11112/7/2026VN");

            XWPFTable operator = document.createTable(2, 2);
            operator.getRow(0).getCell(0).setText("Name: VIETNAM AIRLINES");
            operator.getRow(0).getCell(1)
                    .setText("M\u00e3 IATA (n\u1ebfu c\u00f3): VN");
            operator.getRow(1).getCell(0)
                    .setText("\u0110\u1ecba ch\u1ec9 b\u01b0u \u0111i\u1ec7n: "
                            + "200 Nguyen Son, Ha Noi");
            operator.getRow(1).getCell(1)
                    .setText("M\u00e3 ICAO (n\u1ebfu c\u00f3): HVN");

            document.createParagraph().createRun()
                    .setText("2.1. L\u1ecaCH BAY G\u1ed0C");
            scheduleTable(document, new String[][] {
                    {"VN100", "04JUL26", "04JUL26", "6", "SGN", "1000",
                            "VCL", "1125", "321/320"}
            });

            document.createParagraph().createRun()
                    .setText("2.2. L\u1ecaCH BAY M\u1edaI");
            scheduleTable(document, new String[][] {
                    {"VN1466", "04JUL26", "04JUL26", "6", "SGN", "0905",
                            "VCL", "1030", "321/320"}
            });

            document.createParagraph().createRun()
                    .setText("2.5. CHUY\u1ebeN B\u1ed4 SUNG");
            scheduleTable(document, new String[][] {
                    {"VN7180", "04JUL26", "04JUL26", "6", "HAN", "1715",
                            "DAD", "1840", "321/320"}
            });

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
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
