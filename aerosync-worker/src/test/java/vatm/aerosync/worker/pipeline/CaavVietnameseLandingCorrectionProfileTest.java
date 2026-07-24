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

class CaavVietnameseLandingCorrectionProfileTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsOnlyTheNewBambooSchedule() throws Exception {
        SchedulePermit permit = parser.parse(createDocument(), "LD-545.docx");

        assertPermit(permit);
    }

    @Test
    void parse_mapsConfiguredRealDocument() {
        String samplePath = System.getProperty("permit.ld545.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.ld545.sample.path to validate the real correction document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured correction document does not exist");

        assertPermit(parser.parse(file, file.getFileName().toString()));
    }

    private void assertPermit(SchedulePermit permit) {
        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-545/02/2025VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD-545/02/2025");
        assertThat(permit.permitNumber()).isEqualTo("545");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2025, 2, 11));
        assertThat(permit.operatorId()).isEqualTo("BAV");
        assertThat(permit.reference())
                .isEqualTo("LD- 38/B/W/2024VN; LD- 38/A/W/2024VN");
        assertThat(permit.billingAddress()).contains("SỐ 6 TÂN SƠN");
        assertThat(permit.permitType()).isEqualTo("LD");
        assertThat(permit.version()).isEqualTo("A");
        assertThat(permit.season()).isEqualTo("W");
        assertThat(permit.validHours()).isEqualTo(24);
        assertThat(permit.flightType()).isEqualTo("NO");
        assertThat(permit.iataAirportsAllowed()).isTrue();
        assertThat(permit.emptyAirwaysAllowed()).isTrue();

        assertThat(permit.flights()).hasSize(2);
        assertThat(permit.flights().getFirst()).satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("QH1123");
            assertThat(flight.purposeId()).isEqualTo("PAX");
            assertThat(flight.craftId()).isEqualTo(10L);
            assertThat(flight.mtow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(flight.serviceDays()).isEqualTo("0000560");
            assertThat(flight.fromAirport()).isEqualTo("UIH");
            assertThat(flight.toAirport()).isEqualTo("SGN");
            assertThat(flight.etd()).isEqualTo("0200");
            assertThat(flight.eta()).isEqualTo("0255");
            assertThat(flight.via()).isNull();
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2025, 2, 14));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2025, 2, 15));
            assertThat(flight.remark()).isEqualTo("PAX 320/32Q/32N/321");
        });
        assertThat(permit.flights().get(1)).satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("QH263");
            assertThat(flight.serviceDays()).isEqualTo("1230567");
            assertThat(flight.fromAirport()).isEqualTo("HAN");
            assertThat(flight.toAirport()).isEqualTo("SGN");
            assertThat(flight.etd()).isEqualTo("1115");
            assertThat(flight.eta()).isEqualTo("1320");
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2025, 2, 15));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2025, 2, 21));
        });

        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "LD-545.docx", "ld545", FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        assertDoesNotThrow(() -> new BusinessRuleValidatorStep().validate(context));
    }

    private Path createDocument() throws Exception {
        Path file = tempDir.resolve("LD-545.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HÀ NỘI, NGÀY 11/02/2025");
            paragraph(document, "CỤC HKVN CẤP PHÉP BAY LD-545/02/2025VN NHƯ SAU:");
            paragraph(document, "CAAV XÁC NHẬN SỬA ĐỔI NHƯ SAU");
            paragraph(document, "1. NGƯỜI KHAI THÁC: BAMBOO AIRWAYS/QH ĐỊA CHỈ: SỐ 6 TÂN SƠN, THÀNH PHỐ HỒ CHÍ MINH");
            paragraph(document, "2.1 LỊCH BAY CŨ");
            table(document,
                    new String[] {"SỐ HIỆU CHUYẾN BAY", "HIỆU LỰC TỪ", "HIỆU LỰC ĐẾN",
                            "NGÀY KHAI THÁC", "SÂN BAY CẤT CÁNH", "GIỜ DỰ KIẾN CẤT CÁNH",
                            "SÂN BAY HẠ CÁNH", "GIỜ DỰ KIẾN HẠ CÁNH", "TÀU BAY",
                            "PHÉP BAY LIÊN QUAN"},
                    new String[][] {
                            {"QH1123", "14-Feb-25", "15-Feb-25", "….56.", "UIH", "6:15",
                                    "SGN", "7:40", "320/32Q/32N/321", "LD- 38/B/W/2024VN"},
                            {"QH263", "15-Feb-25", "21-Feb-25", "123.567", "HAN", "12:05",
                                    "SGN", "14:15", "320/32Q/32N/321", "LD- 38/A/W/2024VN"}
                    });
            paragraph(document, "2.2 LỊCH BAY MỚI");
            table(document,
                    new String[] {"SỐ HIỆU CHUYẾN BAY", "HIỆU LỰC TỪ", "HIỆU LỰC ĐẾN",
                            "NGÀY KHAI THÁC", "SÂN BAY CẤT CÁNH", "GIỜ DỰ KIẾN CẤT CÁNH",
                            "SÂN BAY HẠ CÁNH", "GIỜ DỰ KIẾN HẠ CÁNH", "TÀU BAY"},
                    new String[][] {
                            {"QH1123", "14-Feb-25", "15-Feb-25", "….56.", "UIH", "2:00",
                                    "SGN", "2:55", "320/32Q/32N/321"},
                            {"QH263", "15-Feb-25", "21-Feb-25", "123.567", "HAN", "11:15",
                                    "SGN", "13:20", "320/32Q/32N/321"}
                    });
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
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
