package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import vatm.aerosync.common.enums.FileType;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ParserStep {

    private final ObjectMapper objectMapper;

    public ParserStep(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void parse(ProcessingContext context) {
        List<FlightRow> rows = switch (context.getFileType()) {
            case CSV -> parseCsv(context.getFilePath(), context.getOriginalFileName());
            case JSON -> parseJson(context.getFilePath(), context.getOriginalFileName());
            case XML -> parseXml(context.getFilePath(), context.getOriginalFileName());
            case XLSX -> parseXlsx(context.getFilePath(), context.getOriginalFileName());
        };
        if (rows.isEmpty()) {
            throw new FormatValidationException(context.getOriginalFileName(), "No data rows found");
        }
        context.getRows().addAll(rows);
    }

    List<FlightRow> parseCsv(java.nio.file.Path file, String fileName) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return List.of();
            }
            String[] headers = lines.getFirst().toLowerCase().split(",");
            Map<String, Integer> index = headerIndex(headers);
            List<FlightRow> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] values = line.split(",", -1);
                rows.add(new FlightRow(
                        valueAt(values, index.get("callsign")),
                        valueAt(values, index.get("from")),
                        valueAt(values, index.get("to")),
                        parseDate(valueAt(values, index.get("dateflight")), fileName)));
            }
            return rows;
        } catch (FormatValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new FormatValidationException(fileName, "Failed to parse CSV: " + e.getMessage());
        }
    }

    List<FlightRow> parseJson(java.nio.file.Path file, String fileName) {
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            JsonNode array = root.isArray() ? root : root.get("flights");
            if (array == null || !array.isArray()) {
                throw new FormatValidationException(fileName, "JSON must be an array or contain a flights array");
            }
            List<FlightRow> rows = new ArrayList<>();
            for (JsonNode node : array) {
                rows.add(mapNode(node, fileName));
            }
            return rows;
        } catch (FormatValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new FormatValidationException(fileName, "Failed to parse JSON: " + e.getMessage());
        }
    }

    List<FlightRow> parseXml(java.nio.file.Path file, String fileName) {
        try {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(file.toFile());
            NodeList flights = document.getElementsByTagName("flight");
            List<FlightRow> rows = new ArrayList<>();
            for (int i = 0; i < flights.getLength(); i++) {
                Element flight = (Element) flights.item(i);
                rows.add(new FlightRow(
                        text(flight, "callsign"),
                        text(flight, "from"),
                        text(flight, "to"),
                        parseDate(text(flight, "dateFlight"), fileName)));
            }
            return rows;
        } catch (FormatValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new FormatValidationException(fileName, "Failed to parse XML: " + e.getMessage());
        }
    }

    List<FlightRow> parseXlsx(java.nio.file.Path file, String fileName) {
        try (InputStream in = Files.newInputStream(file);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return List.of();
            }
            Map<String, Integer> index = new HashMap<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String header = formatter.formatCellValue(headerRow.getCell(c)).trim().toLowerCase();
                index.put(header, c);
            }
            List<FlightRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String callsign = cell(row, index.get("callsign"), formatter);
                if (callsign == null || callsign.isBlank()) {
                    continue;
                }
                rows.add(new FlightRow(
                        callsign,
                        cell(row, index.get("from"), formatter),
                        cell(row, index.get("to"), formatter),
                        cellDate(row, index.get("dateflight"), formatter, fileName)));
            }
            return rows;
        } catch (FormatValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new FormatValidationException(fileName, "Failed to parse XLSX: " + e.getMessage());
        }
    }

    private FlightRow mapNode(JsonNode node, String fileName) {
        return new FlightRow(
                field(node, "callsign"),
                field(node, "from"),
                field(node, "to"),
                parseDate(field(node, "dateFlight", "dateflight"), fileName));
    }

    private String field(JsonNode node, String... names) {
        for (String name : names) {
            if (node.has(name) && !node.get(name).isNull()) {
                return node.get(name).asText();
            }
        }
        return "";
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private Map<String, Integer> headerIndex(String[] headers) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            index.put(headers[i].trim(), i);
        }
        return index;
    }

    private String valueAt(String[] values, Integer idx) {
        if (idx == null || idx >= values.length) {
            return "";
        }
        return values[idx].trim();
    }

    private String cell(Row row, Integer idx, DataFormatter formatter) {
        if (idx == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(idx)).trim();
    }

    private LocalDate cellDate(Row row, Integer idx, DataFormatter formatter, String fileName) {
        if (idx == null) {
            throw new FormatValidationException(fileName, "Missing dateFlight value");
        }
        Cell cell = row.getCell(idx);
        if (cell == null) {
            throw new FormatValidationException(fileName, "Missing dateFlight value");
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double numeric = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell) || DateUtil.isValidExcelDate(numeric)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
        }
        return parseDate(formatter.formatCellValue(cell).trim(), fileName);
    }

    private LocalDate parseDate(String raw, String fileName) {
        if (raw == null || raw.isBlank()) {
            throw new FormatValidationException(fileName, "Missing dateFlight value");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new FormatValidationException(fileName, "Invalid dateFlight: " + raw);
        }
    }
}
