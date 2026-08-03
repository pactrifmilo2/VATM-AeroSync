package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileCopyUtils;
import vatm.aerosync.worker.model.FlightRow;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParserStepTest {

    private ParserStep parserStep;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        parserStep = new ParserStep(
                mapper,
                new DocxSchedulePermitParser(),
                new LegacyDocRevisionPermitParser());
    }

    @Test
    void parseCsv_readsFlightRows() throws Exception {
        Path file = copySample("samples/valid-flights.csv", "flights.csv");

        List<FlightRow> rows = parserStep.parseCsv(file, "flights.csv");

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().getCallsign()).isEqualTo("vn123");
        assertThat(rows.getFirst().getDateFlight()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void parseJson_readsFlightRows() throws Exception {
        Path file = copySample("samples/valid-flights.json", "flights.json");

        List<FlightRow> rows = parserStep.parseJson(file, "flights.json");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getCallsign()).isEqualTo("VN123");
    }

    @Test
    void parseXml_readsFlightRows() throws Exception {
        Path file = copySample("samples/valid-flights.xml", "flights.xml");

        List<FlightRow> rows = parserStep.parseXml(file, "flights.xml");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getFrom()).isEqualTo("HAN");
    }

    @Test
    void parseXlsx_readsExcelDateCells() throws Exception {
        Path file = Files.createTempFile("flights-", ".xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("callsign");
            header.createCell(1).setCellValue("from");
            header.createCell(2).setCellValue("to");
            header.createCell(3).setCellValue("dateflight");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("VN123");
            data.createCell(1).setCellValue("HAN");
            data.createCell(2).setCellValue("SGN");
            Cell dateCell = data.createCell(3);
            dateCell.setCellValue(LocalDate.of(2026, 6, 1));
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<FlightRow> rows = parserStep.parseXlsx(file, "flights.xlsx");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getCallsign()).isEqualTo("VN123");
        assertThat(rows.getFirst().getDateFlight()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void parseXlsx_readsLocaleFormattedDateCellsFromSample() throws Exception {
        Path file = copySample("samples/temp.xlsx", "temp.xlsx");

        List<FlightRow> rows = parserStep.parseXlsx(file, "temp.xlsx");

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().getCallsign()).isEqualTo("MTN");
        assertThat(rows.getFirst().getDateFlight()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(rows.get(1).getCallsign()).isEqualTo("rwqr");
        assertThat(rows.get(1).getDateFlight()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void parseXlsx_readsVnaBaseScheduleWithSectionDateAndSector() throws Exception {
        Path file = Files.createTempFile("vna-base-schedule-", ".xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("LICH_BAY_TUAN");
            Row header = sheet.createRow(4);
            header.createCell(0).setCellValue("Stt");
            header.createCell(5).setCellValue("FLT_NO");
            header.createCell(7).setCellValue("Sector");
            sheet.createRow(5).createCell(0).setCellValue("04/08/2026");
            Row first = sheet.createRow(6);
            first.createCell(0).setCellValue(1);
            first.createCell(5).setCellValue("VN319");
            first.createCell(7).setCellValue("NRT-DAD");
            Row second = sheet.createRow(7);
            second.createCell(0).setCellValue(2);
            second.createCell(5).setCellValue("VN129");
            second.createCell(7).setCellValue("DAD-SGN");
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<FlightRow> rows = parserStep.parseXlsx(file, "vna.xlsx");

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().getCallsign()).isEqualTo("VN319");
        assertThat(rows.getFirst().getFrom()).isEqualTo("NRT");
        assertThat(rows.getFirst().getTo()).isEqualTo("DAD");
        assertThat(rows.getFirst().getDateFlight()).isEqualTo(LocalDate.of(2026, 8, 4));
    }

    @Test
    void parseXlsx_readsConfiguredRegressionWorkbook() {
        String configured = System.getProperty("xlsx.regression.file");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "Set -Dxlsx.regression.file to validate a real workbook");

        Path file = Path.of(configured);
        List<FlightRow> rows = parserStep.parseXlsx(file, file.getFileName().toString());

        assertThat(rows).isNotEmpty();
        System.out.println("XLSX REGRESSION ROWS=" + rows.size());
    }

    private Path copySample(String resource, String name) throws Exception {
        Path target = Files.createTempFile("parser-", name);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            FileCopyUtils.copy(in, Files.newOutputStream(target));
        }
        return target;
    }
}
