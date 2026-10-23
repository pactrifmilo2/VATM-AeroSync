package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AirAsiaOverflightRevisionProfileTest {

    @TempDir
    Path tempDir;

    private final WordPermitDocumentReader reader = new WordPermitDocumentReader();
    private final WordPermitFormatDetector detector =
            new WordPermitFormatDetector(new DocxPermitProfileCatalog());
    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser(reader, detector);

    @Test
    void revisionWithOldAndNewPassengerSchedulesDoesNotUseCargoScheduledProfile() throws Exception {
        Path file = createPassengerRevision();

        assertThat(detector.detect(reader.read(file), file.getFileName().toString()).id())
                .isEqualTo("airline-generic-overflight-revision");

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 07974/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("AXM");
        assertThat(permit.originalFlights()).extracting(flight -> flight.purposeId())
                .containsExactly("PAX", "PAX", "PAX");
        assertThat(permit.flights()).extracting(flight -> flight.purposeId())
                .containsExactly("PAX", "PAX", "PAX");
    }

    private Path createPassengerRevision() throws Exception {
        Path file = tempDir.resolve("OF-7974-RVS1.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "HANOI, 22-Oct-26");
            paragraph(document, "PERMIT NUMBER OF-7974/10/2026VN/RVS1");
            paragraph(document, "Name: AIRASIA BERHAD");
            paragraph(document, "IATA code: AK");
            paragraph(document, "ICAO code: AXM");
            paragraph(document, "3. Schedules: UTC Time");
            paragraph(document, "3.1 OLD");
            schedule(document, "0015", "0455", "0555", "1040", "2210", "0215");
            paragraph(document, "3.2 NEW");
            schedule(document, "0016", "0456", "0556", "1046", "2216", "0216");
            try (OutputStream output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        return file;
    }

    private void schedule(XWPFDocument document,
                          String firstEtd, String firstEta,
                          String secondEtd, String secondEta,
                          String thirdEtd, String thirdEta) {
        XWPFTable table = document.createTable(4, 8);
        String[] headers = {"Flight number", "Effective from", "Effective to", "Days of services",
                "Departure Airport", "ETD", "Arrival Airport", "ETA"};
        String[][] rows = {
                {"AXM270", "25OCT26", "30OCT26", "1234567", "WMKK", firstEtd, "ZSQZ", firstEta},
                {"AXM271", "25OCT26", "30OCT26", "1234567", "ZSQZ", secondEtd, "WMKK", secondEta},
                {"AXM182", "25OCT26", "30OCT26", "1234567", "WMKK", thirdEtd, "VMMC", thirdEta}
        };
        for (int column = 0; column < headers.length; column++) {
            table.getRow(0).getCell(column).setText(headers[column]);
        }
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < headers.length; column++) {
                table.getRow(row + 1).getCell(column).setText(rows[row][column]);
            }
        }
    }

    private void paragraph(XWPFDocument document, String value) {
        document.createParagraph().createRun().setText(value);
    }
}
