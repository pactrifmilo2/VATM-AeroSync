package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AirlineSpecificPermitProfileRegressionTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void vjcProfile_readsEveryRequiredFieldFromTheRealReport() {
        SchedulePermit permit = parse(
                "giangpth_20260730_161507_email_002_LD-2822.docx");

        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-02818/7/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 02818/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("2818");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(permit.operatorId()).isEqualTo("VJC");
        assertThat(permit.billingAddress()).contains("302/3 Phố Kim Mã");
        assertThat(permit.flights()).hasSize(6);

        assertFlight(permit.flights().get(0), "VJC330", "VVPQ", "0055", "VVTS", "0430",
                "1030500", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 31),
                "320/321/32Q/333", "W8 W16 HOẶC W8 W17", "CAR");
        assertFlight(permit.flights().get(5), "VJC715", "VVDN", "0710", "VVPQ", "1200",
                "1234567", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                "320/321/32Q/333", "Q1 W12 W16 W8", "CAR");
    }

    @Test
    void vfcProfile_readsOnlyChangedScheduleFromTheRealReport() {
        SchedulePermit permit = normalizeFlightNumbers(parse(
                "duongvtt_20260730_173614_email_000_LD-2795.7.2026VN_-0V-"
                        + "___utf-8_B_U_G7rEEgxJDhu5RJIFBIw4lQ___BAY_02_-_06AUG26.docx"));

        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-02795/7/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 02795/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("2795");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(permit.operatorId()).isEqualTo("VFC");
        assertThat(permit.reference()).isEqualTo("LD-69/A/S/2026VN");
        assertThat(permit.billingAddress()).isNull();
        assertThat(permit.flights()).hasSize(4);

        assertFlight(permit.flights().get(0), "VFC8059", "VVTS", "2325", "VVCS", "0015+",
                "1000007", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                "ATR72", null, "PAX");
        assertFlight(permit.flights().get(3), "VFC8054", "VVCS", "0230", "VVTS", "0335",
                "0034000", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 6),
                "ATR72", null, "PAX");
    }

    @Test
    void vfcProfile_readsTheOnlyScheduleWhenThereIsNoChangedSchedule() throws Exception {
        Path file = tempDir.resolve("vfc-single-schedule.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Hà Nội, ngày 30/7/2026");
            document.createParagraph().createRun().setText("LD-2800/7/2026VN");

            XWPFTable operator = document.createTable(1, 2);
            operator.getRow(0).getCell(0).setText("Mã IATA: 0V");
            operator.getRow(0).getCell(1).setText("Mã ICAO: VFC");

            document.createParagraph().createRun().setText("LỊCH BAY");
            XWPFTable schedule = document.createTable(2, 10);
            String[] headers = {
                    "Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                    "Ngày trong tuần", "Sân bay cất cánh",
                    "Giờ khởi hành dự kiến", "Sân bay hạ cánh",
                    "Giờ hạ cánh dự kiến", "Loại tàu bay", "Số Phép bay"
            };
            String[] values = {
                    "0V9001", "02Aug26", "02Aug26", "------7",
                    "VVTS", "1200", "VVCS", "1300", "ATR72",
                    "LD-69/A/S/2026VN"
            };
            for (int index = 0; index < headers.length; index++) {
                schedule.getRow(0).getCell(index).setText(headers[index]);
                schedule.getRow(1).getCell(index).setText(values[index]);
            }

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        SchedulePermit permit = normalizeFlightNumbers(
                parser.parse(file, file.getFileName().toString()));

        assertThat(permit.operatorId()).isEqualTo("VFC");
        assertThat(permit.reference()).isEqualTo("LD-69/A/S/2026VN");
        assertThat(permit.flights()).hasSize(1);
        assertFlight(permit.flights().getFirst(), "VFC9001", "VVTS", "1200", "VVCS", "1300",
                "0000007", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 2),
                "ATR72", null, "PAX");
    }

    @Test
    void airlineProfile_reportsMissingRequiredFieldsWithAcceptedHeaders() throws Exception {
        Path file = tempDir.resolve("vjc-missing-eta.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Hà Nội, ngày 30/7/2026");
            document.createParagraph().createRun().setText("LD-2818/7/2026VN");
            XWPFTable operator = document.createTable(2, 2);
            operator.getRow(0).getCell(0).setText("Mã IATA: VJ");
            operator.getRow(0).getCell(1).setText("Mã ICAO: VJC");
            operator.getRow(1).getCell(0).setText("Địa chỉ bưu điện: Hà Nội");
            XWPFTable schedule = document.createTable(2, 7);
            String[] headers = {"Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                    "Ngày trong tuần", "Sân bay cất cánh",
                    "Giờ khởi hành dự kiến", "Sân bay hạ cánh"};
            for (int index = 0; index < headers.length; index++) {
                schedule.getRow(0).getCell(index).setText(headers[index]);
            }
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        assertThatThrownBy(() -> parser.parse(file, file.getFileName().toString()))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("vjc-vietnamese-landing-issued")
                .hasMessageContaining("missing required fields: eta")
                .hasMessageContaining("Giờ hạ cánh dự kiến")
                .hasMessageContaining("Giờ dự kiến hạ cánh");
    }

    private SchedulePermit parse(String fileName) {
        Path file = Path.of("..", fileName).toAbsolutePath().normalize();
        return parser.parse(file, file.getFileName().toString());
    }

    private SchedulePermit normalizeFlightNumbers(SchedulePermit permit) {
        PermitOperatorCatalog operators = new PermitOperatorCatalog();
        return permit.withFlights(permit.flights().stream()
                .map(flight -> flight.withFlightNumber(
                        operators.normalizeFlightNumber(
                                flight.flightNumber(), permit.operatorId())))
                .toList());
    }

    private void assertFlight(ScheduleFlight flight,
                              String number,
                              String from,
                              String etd,
                              String to,
                              String eta,
                              String days,
                              LocalDate begin,
                              LocalDate end,
                              String aircraft,
                              String via,
                              String purpose) {
        assertThat(flight.flightNumber()).isEqualTo(number);
        assertThat(flight.fromAirport()).isEqualTo(from);
        assertThat(flight.etd()).isEqualTo(etd);
        assertThat(flight.toAirport()).isEqualTo(to);
        assertThat(flight.eta()).isEqualTo(eta);
        assertThat(flight.serviceDays()).isEqualTo(days);
        assertThat(flight.beginDate()).isEqualTo(begin);
        assertThat(flight.endDate()).isEqualTo(end);
        assertThat(flight.sourceAircraftType()).isEqualTo(aircraft);
        assertThat(flight.via()).isEqualTo(via);
        assertThat(flight.purposeId()).isEqualTo(purpose);
    }
}
