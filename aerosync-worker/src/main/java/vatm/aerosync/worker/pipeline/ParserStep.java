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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ParserStep {

    private final ObjectMapper objectMapper;
    private final DocxSchedulePermitParser docxSchedulePermitParser;
    private final LegacyDocRevisionPermitParser legacyDocRevisionPermitParser;

    public ParserStep(ObjectMapper objectMapper,
                      DocxSchedulePermitParser docxSchedulePermitParser,
                      LegacyDocRevisionPermitParser legacyDocRevisionPermitParser) {
        this.objectMapper = objectMapper;
        this.docxSchedulePermitParser = docxSchedulePermitParser;
        this.legacyDocRevisionPermitParser = legacyDocRevisionPermitParser;
    }

    public void parse(ProcessingContext context) {
        if (context.getFileType() == vatm.aerosync.common.enums.FileType.DOCX) {
            context.setSchedulePermit(docxSchedulePermitParser.parse(
                    context.getFilePath(), context.getOriginalFileName()));
            return;
        }
        if (context.getFileType() == vatm.aerosync.common.enums.FileType.DOC) {
            context.setSchedulePermit(legacyDocRevisionPermitParser.parse(
                    context.getFilePath(), context.getOriginalFileName()));
            return;
        }
        List<FlightRow> rows = switch (context.getFileType()) {
            case CSV -> parseCsv(context.getFilePath(), context.getOriginalFileName());
            case JSON -> parseJson(context.getFilePath(), context.getOriginalFileName());
            case XML -> parseXml(context.getFilePath(), context.getOriginalFileName());
            case XLSX -> parseXlsx(context.getFilePath(), context.getOriginalFileName());
            case DOC -> throw new IllegalStateException("DOC parser dispatch failed");
            case DOCX -> throw new IllegalStateException("DOCX parser dispatch failed");
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
            Row headerRow = findXlsxHeader(sheet, formatter);
            if (headerRow == null) {
                return List.of();
            }
            Map<String, Integer> index = new HashMap<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String header = normalizeXlsxHeader(formatter.formatCellValue(headerRow.getCell(c)));
                index.put(header, c);
            }
            Integer callsignColumn = firstColumn(index, "callsign", "fltno", "flightnumber");
            Integer fromColumn = firstColumn(index, "from", "departureairport", "depairport");
            Integer toColumn = firstColumn(index, "to", "arrivalairport", "arrairport");
            Integer routeColumn = firstColumn(index, "sector", "route");
            Integer dateColumn = firstColumn(index, "dateflight", "flightdate", "date");
            List<FlightRow> rows = new ArrayList<>();
            LocalDate sectionDate = null;
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                LocalDate detectedSectionDate = sectionDate(row, formatter);
                if (detectedSectionDate != null) {
                    sectionDate = detectedSectionDate;
                    continue;
                }
                String callsign = cell(row, callsignColumn, formatter);
                if (callsign == null || callsign.isBlank()) {
                    continue;
                }
                String from = cell(row, fromColumn, formatter);
                String to = cell(row, toColumn, formatter);
                if ((from == null || from.isBlank() || to == null || to.isBlank())
                        && routeColumn != null) {
                    String[] route = cell(row, routeColumn, formatter)
                            .toUpperCase()
                            .split("\\s*[-/\\u2013\\u2014]\\s*", 2);
                    if (route.length == 2) {
                        from = route[0];
                        to = route[1];
                    }
                }
                LocalDate flightDate = dateColumn == null
                        ? sectionDate
                        : cellDate(row, dateColumn, formatter, fileName);
                if (flightDate == null) {
                    throw new FormatValidationException(
                            fileName, "Missing dateFlight value for flight " + callsign);
                }
                rows.add(new FlightRow(
                        callsign,
                        from,
                        to,
                        flightDate));
            }
            return rows;
        } catch (FormatValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new FormatValidationException(fileName, "Failed to parse XLSX: " + e.getMessage());
        }
    }

    private Row findXlsxHeader(Sheet sheet, DataFormatter formatter) {
        int lastCandidate = Math.min(sheet.getLastRowNum(), 100);
        for (int rowNumber = 0; rowNumber <= lastCandidate; rowNumber++) {
            Row row = sheet.getRow(rowNumber);
            if (row == null) {
                continue;
            }
            java.util.Set<String> headers = new java.util.HashSet<>();
            for (int column = 0; column < row.getLastCellNum(); column++) {
                headers.add(normalizeXlsxHeader(formatter.formatCellValue(row.getCell(column))));
            }
            boolean hasCallsign = headers.contains("callsign")
                    || headers.contains("fltno")
                    || headers.contains("flightnumber");
            boolean hasRoute = headers.contains("sector") || headers.contains("route")
                    || (headers.contains("from") && headers.contains("to"));
            if (hasCallsign && hasRoute) {
                return row;
            }
        }
        return null;
    }

    private String normalizeXlsxHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]", "");
    }

    private Integer firstColumn(Map<String, Integer> index, String... aliases) {
        for (String alias : aliases) {
            Integer column = index.get(alias);
            if (column != null) {
                return column;
            }
        }
        return null;
    }

    private LocalDate sectionDate(Row row, DataFormatter formatter) {
        int populated = 0;
        for (int column = 0; column < row.getLastCellNum(); column++) {
            if (!formatter.formatCellValue(row.getCell(column)).isBlank()) {
                populated++;
            }
        }
        if (populated != 1) {
            return null;
        }
        Cell cell = row.getCell(row.getFirstCellNum());
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = formatter.formatCellValue(cell).trim();
        for (DateTimeFormatter format : List.of(
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next supported section-date format.
            }
        }
        return null;
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
