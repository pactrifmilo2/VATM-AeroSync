package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplacementScheduleSelectionTest {

    @TempDir
    Path tempDir;

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void importsOnlyReplacementTablesRegardlessOfRevisionSuffix() throws Exception {
        List<String> suffixes = List.of("", "/REV1", " REV:", " RVS:", " RVS1");

        for (int index = 0; index < suffixes.size(); index++) {
            Path file = createVjcDocument(index, suffixes.get(index));
            SchedulePermit permit = parser.parse(file, file.getFileName().toString());

            assertThat(permit.operatorId()).as("suffix %s", suffixes.get(index)).isEqualTo("VJC");
            assertThat(permit.revision()).as("suffix %s", suffixes.get(index)).isTrue();
            assertThat(permit.flights())
                    .as("only new/revised schedules for suffix %s", suffixes.get(index))
                    .extracting(flight -> flight.flightNumber())
                    .containsExactly("VJC200", "VJC300")
                    .doesNotContain("VJC100");
            assertThat(permit.originalFlights())
                    .as("old/original schedule for reconciliation")
                    .extracting(flight -> flight.flightNumber())
                    .containsExactly("VJC100");
        }
    }

    private Path createVjcDocument(int index, String permitSuffix) throws Exception {
        Path file = tempDir.resolve("LD-900" + index + "-VJC-replacement.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HÀ NỘI, NGÀY 04/8/2026");
            paragraph(document, "Phép bay số: LD-900" + index + "/8/2026VN" + permitSuffix);
            table(document,
                    new String[] {"Thông tin hãng hàng không"},
                    new String[][] {
                            {"TÊN: CÔNG TY CỔ PHẦN HÀNG KHÔNG VIETJET"},
                            {"MÃ IATA: VJ   MÃ ICAO: VJC"},
                            {"ĐỊA CHỈ BƯU ĐIỆN: 302/3 KIM MÃ, HÀ NỘI"}
                    });

            paragraph(document, "2.1. Bảng chuyến bay gốc");
            schedule(document, "VJ100", "SGN", "HAN", "0100", "0300");

            paragraph(document, "2.2. Bảng chuyến bay sửa đổi - chuyến bay quốc tế");
            schedule(document, "VJ200", "SGN", "HAN", "0200", "0400");

            paragraph(document, "2.3. Chuyến bay mới - chuyến bay quốc nội");
            schedule(document, "VJ300", "HAN", "DAD", "0500", "0630");

            paragraph(document, "3. Loại tàu bay");
            table(document,
                    new String[] {"Loại tàu bay", "Số đăng ký"},
                    new String[][] {{"A321", "VN-A123"}});

            paragraph(document, "4. Đường hàng không");
            table(document,
                    new String[] {"Chặng bay", "Đường hàng không"},
                    new String[][] {
                            {"SGN-HAN", "DCT"},
                            {"HAN-DAD", "DCT"}
                    });

            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document,
                          String flightNumber,
                          String from,
                          String to,
                          String etd,
                          String eta) {
        table(document,
                new String[] {
                        "Số hiệu chuyến bay", "Hiệu lực từ", "Hiệu lực đến",
                        "Ngày trong tuần", "Sân bay cất cánh", "Giờ khởi hành dự kiến",
                        "Sân bay hạ cánh", "Giờ hạ cánh dự kiến"
                },
                new String[][] {{
                        flightNumber, "\u200B05AUG26", "\uFEFF05AUG26", "1------",
                        from, etd, to, eta
                }});
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
