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

class Spa017SeasonalLandingRevisionProfileTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsOnlyTheNewSeasonalLandingSchedule() throws Exception {
        assertPermit(parser.parse(createDocument(), "SPA017-REV9.docx"));
    }

    @Test
    void parse_mapsConfiguredRealDocument() {
        String samplePath = System.getProperty("permit.spa017.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.spa017.sample.path to validate the real SPA017 document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured SPA017 document does not exist");

        assertPermit(parser.parse(file, file.getFileName().toString()));
    }

    private void assertPermit(SchedulePermit permit) {
        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-32/B/S/2026VN/REV9");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 0032B/S/CHK/2026");
        assertThat(permit.permitNumber()).isEqualTo("32B");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(permit.operatorId()).isEqualTo("SPQ");
        assertThat(permit.reference()).isEqualTo("LD-32/B/S/2026VN");
        assertThat(permit.permitType()).isEqualTo("LD");
        assertThat(permit.version()).isEqualTo("B");
        assertThat(permit.season()).isEqualTo("S");
        assertThat(permit.validHours()).isEqualTo(24);
        assertThat(permit.flightType()).isEqualTo("SC");
        assertThat(permit.iataAirportsAllowed()).isTrue();
        assertThat(permit.emptyAirwaysAllowed()).isFalse();
        assertThat(permit.billingAddress()).contains("Sun Grand City");

        assertThat(permit.flights()).hasSize(2);
        assertThat(permit.flights().get(0)).satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("9G2953");
            assertThat(flight.purposeId()).isEqualTo("PAX");
            assertThat(flight.craftId()).isEqualTo(4366L);
            assertThat(flight.mtow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(flight.serviceDays()).isEqualTo("0204507");
            assertThat(flight.fromAirport()).isEqualTo("CXR");
            assertThat(flight.toAirport()).isEqualTo("PQC");
            assertThat(flight.etd()).isEqualTo("0730");
            assertThat(flight.eta()).isEqualTo("0850");
            assertThat(flight.via()).isEqualTo("W15/W1/W17");
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 23));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
            assertThat(flight.remark()).isEqualTo("PAX 321/32Q/32N");
        });
        assertThat(permit.flights().get(1)).satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("9G1978");
            assertThat(flight.serviceDays()).isEqualTo("1000000");
            assertThat(flight.fromAirport()).isEqualTo("PQC");
            assertThat(flight.toAirport()).isEqualTo("SGN");
            assertThat(flight.etd()).isEqualTo("1050");
            assertThat(flight.eta()).isEqualTo("1155");
            assertThat(flight.via()).isEqualTo("W8/W16");
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 27));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        });

        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "SPA017-REV9.docx", "spa017", FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        assertDoesNotThrow(() -> new BusinessRuleValidatorStep().validate(context));
    }

    private Path createDocument() throws Exception {
        Path file = tempDir.resolve("SPA017-REV9.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "Phép bay sửa đổi");
            paragraph(document, "Hà Nội,22/07/2026");
            paragraph(document, "Phép bay số:LD-32/B/S/2026VN/REV9");
            paragraph(document, "Tham chiếu phép bay số: LD-32/B/S/2026VN");
            paragraph(document, "1. Hãng hàng không");
            table(document,
                    new String[] {"Tên: Công ty TNHH Hàng không Mặt trời Phú Quốc"},
                    new String[][] {
                            {"Mã IATA: 9G Mã ICAO: SPQ"},
                            {"Địa chỉ bưu điện: Tầng 4, Tòa nhà Sun Grand City, Hà Nội"}
                    });
            paragraph(document, "2. Lịch bay (Giờ UTC)");
            paragraph(document, "2.1. Lịch bay gốc");
            schedule(document, "09:20", "10:40", "11:05", "12:15");
            paragraph(document, "2.2. Lịch bay mới (sửa đổi)");
            schedule(document, "07:30", "08:50", "10:50", "11:55");
            paragraph(document, "CÁC CHI TIẾT KHÁC KHÔNG THAY ĐỔI");
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document,
                          String firstEtd,
                          String firstEta,
                          String secondEtd,
                          String secondEta) {
        table(document,
                new String[] {"Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                        "Ngày trong tuần", "Sân bay đi", "Giờ dự kiến đi",
                        "Sân bay đến", "Giờ dự kiến đến"},
                new String[][] {
                        {"9G2953", "23-Jul-26", "31-Jul-26", "-2-45-7",
                                "CXR", firstEtd, "PQC", firstEta},
                        {"9G1978", "27-Jul-26", "27-Jul-26", "1------",
                                "PQC", secondEtd, "SGN", secondEta}
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
