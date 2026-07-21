package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DocxSchedulePermitParser {

    private static final Pattern PERMIT_PATTERN = Pattern.compile(
            "(?i)\\bOF[-\\s]?(\\d{1,5})/(\\d{1,2})/(20\\d{2})VN\\b");
    private static final Pattern PERMIT_DATE_PATTERN = Pattern.compile(
            "(?i)HANOI,\\s*(\\d{1,2}-[A-Z]{3}-\\d{2,4})");
    private static final Pattern ICAO_PATTERN = Pattern.compile("(?i)ICAO\\s*CODE:\\s*([A-Z0-9]{3})");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?i)POSTAL\\s+ADDRESS:\\s*(.+)");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("(?i)\\(REF\\.\\s*([^)]+)\\)");
    private static final DateTimeFormatter SHORT_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d-MMM-yy")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter COMPACT_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dMMMyy")
            .toFormatter(Locale.ENGLISH);

    public SchedulePermit parse(Path file, String fileName) {
        try (InputStream input = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(input)) {
            List<List<List<String>>> tables = document.getTables().stream()
                    .map(this::tableRows)
                    .toList();
            String paragraphText = document.getParagraphs().stream()
                    .map(paragraph -> clean(paragraph.getText()))
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining("\n"));
            String tableText = tables.stream()
                    .flatMap(List::stream)
                    .flatMap(List::stream)
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining("\n"));
            String rawContent = paragraphText + "\n" + tableText;

            Matcher permitMatcher = require(PERMIT_PATTERN, rawContent, fileName, "Permit number not found");
            String permitNumber = permitMatcher.group(1);
            String permitYear = permitMatcher.group(3);
            String sourcePermitNumber = permitMatcher.group();
            String normalizedPermitId = "O/F %s/S/CHK/%s".formatted(
                    permitNumber.matches("\\d+") ? "%05d".formatted(Integer.parseInt(permitNumber)) : permitNumber,
                    permitYear);

            Matcher dateMatcher = require(PERMIT_DATE_PATTERN, paragraphText, fileName, "Permit date not found");
            LocalDate permitDate = parseDate(dateMatcher.group(1), SHORT_DATE, fileName, "permit date");
            String operatorId = firstMatch(ICAO_PATTERN, tableText, 1);
            if (operatorId == null) {
                throw invalid(fileName, "Carrier ICAO code not found");
            }
            if (!paragraphText.toUpperCase(Locale.ROOT).contains("SCHEDULES: UTC TIME")) {
                throw invalid(fileName, "Document is not a scheduled permit");
            }
            if (!paragraphText.toUpperCase(Locale.ROOT).contains("CARGO FLIGHT")) {
                throw invalid(fileName, "Only cargo scheduled permits are currently supported");
            }

            String billingAddress = firstMatch(ADDRESS_PATTERN, tableText, 1);
            String reference = firstMatch(REFERENCE_PATTERN, paragraphText, 1);
            String aircraftRemark = aircraftRemark(tables);
            List<RouteRow> routes = routeRows(tables);
            List<ScheduleFlight> flights = scheduleFlights(tables, routes, aircraftRemark, fileName);
            if (flights.isEmpty()) {
                throw invalid(fileName, "No scheduled flight rows found");
            }

            return new SchedulePermit(
                    sourcePermitNumber.toUpperCase(Locale.ROOT),
                    normalizedPermitId,
                    permitNumber,
                    "CHK",
                    "O/F",
                    "A",
                    "S",
                    permitDate,
                    operatorId.toUpperCase(Locale.ROOT),
                    reference,
                    72,
                    billingAddress,
                    "SC",
                    rawContent,
                    flights);
        } catch (FormatValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid(fileName, "Failed to parse DOCX permit: " + exception.getMessage());
        }
    }

    private List<ScheduleFlight> scheduleFlights(List<List<List<String>>> tables,
                                                 List<RouteRow> routes,
                                                 String aircraftRemark,
                                                 String fileName) {
        List<List<String>> table = findTable(tables, Set.of(
                "flightnumber", "efffrom", "effto", "daysofservices", "depairport", "etd", "arrairport"));
        if (table == null || table.size() < 2) {
            return List.of();
        }
        Map<String, Integer> columns = columns(table.getFirst());
        List<ScheduleFlight> flights = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            String flightNumber = value(row, columns, "flightnumber").toUpperCase(Locale.ROOT);
            if (flightNumber.isBlank()) {
                continue;
            }
            LocalDate beginDate = parseDate(value(row, columns, "efffrom"), COMPACT_DATE, fileName, "effective-from date");
            LocalDate endDate = parseDate(value(row, columns, "effto"), COMPACT_DATE, fileName, "effective-to date");
            String from = value(row, columns, "depairport").toUpperCase(Locale.ROOT);
            String to = value(row, columns, "arrairport").toUpperCase(Locale.ROOT);
            String via = routes.stream()
                    .filter(route -> route.matches(from, to))
                    .map(RouteRow::airways)
                    .findFirst()
                    .orElseGet(() -> routes.isEmpty() ? null : routes.getFirst().airways());
            flights.add(new ScheduleFlight(
                    "CAR",
                    1935L,
                    BigDecimal.ZERO,
                    flightNumber,
                    null,
                    normalizeDays(value(row, columns, "daysofservices"), fileName),
                    from,
                    to,
                    normalizeTime(value(row, columns, "etd")),
                    null,
                    via,
                    beginDate,
                    endDate,
                    aircraftRemark));
        }
        return flights;
    }

    private List<RouteRow> routeRows(List<List<List<String>>> tables) {
        List<List<String>> table = findTable(tables, Set.of("sector", "airways"));
        if (table == null || table.size() < 2) {
            return List.of();
        }
        Map<String, Integer> columns = columns(table.getFirst());
        List<RouteRow> routes = new ArrayList<>();
        for (int index = 1; index < table.size(); index++) {
            List<String> row = table.get(index);
            String sector = value(row, columns, "sector").toUpperCase(Locale.ROOT);
            String airways = value(row, columns, "airways").toUpperCase(Locale.ROOT);
            if (sector.matches("[A-Z]{4}\\s*[-–—]\\s*[A-Z]{4}") && !airways.isBlank()) {
                String[] airports = sector.split("\\s*[-–—]\\s*");
                routes.add(new RouteRow(airports[0], airports[1], normalizeAirways(airways)));
            }
        }
        return routes;
    }

    private String aircraftRemark(List<List<List<String>>> tables) {
        List<List<String>> table = findTable(tables, Set.of("aircrafttype", "registrationmarks"));
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (table != null && table.size() > 1) {
            Map<String, Integer> columns = columns(table.getFirst());
            for (int index = 1; index < table.size(); index++) {
                String type = value(table.get(index), columns, "aircrafttype").toUpperCase(Locale.ROOT);
                if (!type.isBlank()) {
                    types.add(type);
                }
            }
        }
        return types.isEmpty() ? "CAR" : "CAR " + String.join("/", types);
    }

    private List<List<String>> findTable(List<List<List<String>>> tables, Set<String> requiredHeaders) {
        return tables.stream()
                .filter(table -> !table.isEmpty())
                .filter(table -> columns(table.getFirst()).keySet().containsAll(requiredHeaders))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Integer> columns(List<String> header) {
        return java.util.stream.IntStream.range(0, header.size())
                .boxed()
                .collect(Collectors.toMap(index -> canonical(header.get(index)), Function.identity(), (a, b) -> a));
    }

    private String value(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.size() ? "" : clean(row.get(index));
    }

    private List<List<String>> tableRows(XWPFTable table) {
        return table.getRows().stream()
                .map(this::rowCells)
                .toList();
    }

    private List<String> rowCells(XWPFTableRow row) {
        return row.getTableCells().stream()
                .map(XWPFTableCell::getText)
                .map(this::clean)
                .toList();
    }

    private String normalizeDays(String raw, String fileName) {
        String compact = raw.replaceAll("\\s+", "");
        if (compact.length() != 7) {
            throw invalid(fileName, "Day-of-service value must contain seven positions: " + raw);
        }
        StringBuilder result = new StringBuilder(7);
        for (int index = 0; index < 7; index++) {
            char expected = (char) ('1' + index);
            char actual = compact.charAt(index);
            result.append(actual == expected ? expected : '0');
        }
        return result.toString();
    }

    private String normalizeAirways(String value) {
        return value.trim().replaceAll("\\s*[-–—]\\s*", "/");
    }

    private String normalizeTime(String value) {
        return value.replace(":", "").replaceAll("\\s+", "");
    }

    private String canonical(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private LocalDate parseDate(String value, DateTimeFormatter formatter, String fileName, String field) {
        try {
            return LocalDate.parse(value.trim(), formatter);
        } catch (DateTimeParseException exception) {
            throw invalid(fileName, "Invalid " + field + ": " + value);
        }
    }

    private Matcher require(Pattern pattern, String text, String fileName, String message) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw invalid(fileName, message);
        }
        return matcher;
    }

    private String firstMatch(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? clean(matcher.group(group)) : null;
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }

    private record RouteRow(String from, String to, String airways) {
        boolean matches(String candidateFrom, String candidateTo) {
            return from.equals(candidateFrom) && to.equals(candidateTo);
        }
    }
}
