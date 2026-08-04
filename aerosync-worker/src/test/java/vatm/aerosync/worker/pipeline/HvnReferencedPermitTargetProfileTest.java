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

import static org.assertj.core.api.Assertions.assertThat;

class HvnReferencedPermitTargetProfileTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_usesNumberedOriginalPermitAsAtfmTarget() throws Exception {
        SchedulePermit permit = parser.parse(
                createDocument("LD-11116/7/2026VN", "LD-2372/6/2026VN", true),
                "LD 2517 HVN.docx");

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 11116/S/CHK/2026");
        assertThat(permit.referencedPermitId()).isEqualTo("LD 02372/S/CHK/2026");
        assertThat(permit.atfmTargetPermitId()).isEqualTo("LD 02372/S/CHK/2026");
        assertThat(permit.flights()).extracting(flight -> flight.flightNumber())
                .containsExactly("HVN1466", "HVN7640");
    }

    @Test
    void parse_usesSeasonalOriginalPermitAsAtfmTarget() throws Exception {
        SchedulePermit permit = parser.parse(
                createDocument("LD-1120/7/2026VN", "LD-68/A/S/2026VN", false),
                "LD 2847 HVN384.docx");

        assertThat(permit.normalizedPermitId()).isEqualTo("LD 01120/S/CHK/2026");
        assertThat(permit.referencedPermitId()).isEqualTo("LD 0068A/S/CHK/2026");
        assertThat(permit.atfmTargetPermitId()).isEqualTo("LD 0068A/S/CHK/2026");
    }

    @Test
    void parse_configuredRealDocumentsUseTheirOriginalPermitTargets() {
        String ld2517 = System.getProperty("permit.ld2517.sample.path");
        String ld2847 = System.getProperty("permit.ld2847.sample.path");
        Assumptions.assumeTrue(ld2517 != null && ld2847 != null,
                "Set both HVN sample paths to validate the real documents");

        SchedulePermit first = parser.parse(Path.of(ld2517), Path.of(ld2517).getFileName().toString());
        SchedulePermit second = parser.parse(Path.of(ld2847), Path.of(ld2847).getFileName().toString());

        assertThat(first.atfmTargetPermitId()).isEqualTo("LD 02372/S/CHK/2026");
        assertThat(second.atfmTargetPermitId()).isEqualTo("LD 0068A/S/CHK/2026");
    }

    private Path createDocument(String currentPermit,
                                String originalPermit,
                                boolean includeNewFlight) throws Exception {
        Path file = tempDir.resolve(currentPermit.startsWith("LD-11116")
                ? "LD 2517 HVN.docx"
                : "LD 2847 HVN384.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HÀ NỘI, 31/7/2026");
            paragraph(document, currentPermit);
            paragraph(document, "Mã IATA: VN   Mã ICAO: HVN");
            paragraph(document, "2.1. Lịch bay gốc (Giờ UTC)");
            schedule(document, originalPermit, false, false);
            paragraph(document, "2.2. Lịch bay mới (Giờ UTC)");
            schedule(document, null, true, includeNewFlight);
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document,
                          String originalPermit,
                          boolean replacement,
                          boolean includeNewFlight) {
        String[] headers = originalPermit == null
                ? new String[] {"Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                        "Ngày trong tuần", "Sân bay cất cánh", "Giờ dự kiến cất cánh",
                        "Sân bay hạ cánh", "Giờ dự kiến hạ cánh", "Loại tàu bay", "Ghi chú"}
                : new String[] {"Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                        "Ngày trong tuần", "Sân bay cất cánh", "Giờ dự kiến cất cánh",
                        "Sân bay hạ cánh", "Giờ dự kiến hạ cánh", "Loại tàu bay",
                        "Số phép bay đã cấp"};
        String flight = originalPermit != null && originalPermit.contains("68/A") ? "VN384" : "VN1466";
        String from = flight.equals("VN384") ? "HAN" : "SGN";
        String to = flight.equals("VN384") ? "HND" : "VCL";
        String[][] rows;
        if (includeNewFlight) {
            rows = new String[][] {
                    {flight, "01AUG26", "01AUG26", "6", from,
                            replacement ? "00:45" : "01:05", to, "06:05", "321/320", "Bay sớm"},
                    {"VN7640", "01AUG26", "01AUG26", "6", "VCL",
                            "11:00", "HAN", "12:25", "321/320", "Chuyến mới"}
            };
        } else {
            rows = new String[][] {{flight, "01AUG26", "01AUG26", "6", from,
                    replacement ? "00:45" : "01:05", to, "06:05", "321/320",
                    originalPermit == null ? "Bay sớm" : originalPermit}};
        }
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
