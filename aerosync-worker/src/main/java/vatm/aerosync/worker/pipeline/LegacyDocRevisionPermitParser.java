package vatm.aerosync.worker.pipeline;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class LegacyDocRevisionPermitParser {

    private static final Pattern PERMIT_PATTERN = Pattern.compile(
            "(?i)\\b(LD)-(\\d{1,5})/([A-Z])/(S)/(20\\d{2})VN(?:/REV(\\d+))?\\b");
    private static final Pattern PERMIT_DATE_PATTERN = Pattern.compile(
            "(?i)HANOI\\s*,\\s*(\\d{1,2}/\\d{1,2}/20\\d{2})");
    private static final Pattern ICAO_PATTERN = Pattern.compile("(?i)ICAO\\s*CODE:\\s*([A-Z0-9]{3})");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "(?i)POSTAL\\s+ADDRESS:\\s*([^\\n]+)");
    private static final Pattern VALID_HOURS_PATTERN = Pattern.compile(
            "(?i)VALIDITY\\s*:[^\\n]*?\\+(\\d+)\\s*HOURS");
    private static final DateTimeFormatter SLASH_DATE = DateTimeFormatter.ofPattern("d/M/uuuu");
    private static final DateTimeFormatter COMPACT_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dMMMyy")
            .toFormatter(Locale.ENGLISH);
    private static final Set<String> SCHEDULE_HEADERS = Set.of(
            "flightnumber", "effectivefrom", "effectiveto", "daysofservices",
            "departureairport", "etd", "arrivalairport", "eta", "aircrafttype");
    private static final Map<String, AircraftMapping> AIRCRAFT = Map.of(
            "767F", new AircraftMapping(4046L, new BigDecimal("185")));

    public SchedulePermit parse(Path file, String fileName) {
        try (InputStream input = Files.newInputStream(file);
             HWPFDocument document = new HWPFDocument(input)) {
            Range range = document.getRange();
            return parseContent(cleanMultiline(range.text()), tables(range), fileName);
        } catch (FormatValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid(fileName, "Failed to parse legacy DOC permit: " + exception.getMessage());
        }
    }

    SchedulePermit parseContent(String rawContent,
                                List<List<List<String>>> tables,
                                String fileName) {
        Matcher permitMatcher = require(
                PERMIT_PATTERN, rawContent, fileName, "Landing permit number not found");
        String permitType = permitMatcher.group(1).toUpperCase(Locale.ROOT);
        String permitNumber = permitMatcher.group(2);
        String version = permitMatcher.group(3).toUpperCase(Locale.ROOT);
        String season = permitMatcher.group(4).toUpperCase(Locale.ROOT);
        String year = permitMatcher.group(5);
        String normalizedPermitId = "%s-%s/%s/%s/%s".formatted(
                permitType, permitNumber, version, season, year);

        Matcher dateMatcher = require(
                PERMIT_DATE_PATTERN, rawContent, fileName, "Permit date not found");
        LocalDate permitDate = parseDate(dateMatcher.group(1), SLASH_DATE, fileName, "permit date");
        String operatorId = firstMatch(ICAO_PATTERN, rawContent, 1);
        if (operatorId == null) {
            throw invalid(fileName, "Carrier ICAO code not found");
        }

        List<List<String>> newSchedule = findNewScheduleTable(tables);
        if (newSchedule == null || newSchedule.size() < 2) {
            throw invalid(fileName, "New schedule table not found");
        }
        List<RouteRow> routes = routeRows(tables);
        if (routes.isEmpty()) {
            throw invalid(fileName, "Airways table not found");
        }
        List<ScheduleFlight> flights = scheduleFlights(newSchedule, routes, fileName);
        if (flights.isEmpty()) {
            throw invalid(fileName, "No new schedule rows found");
        }

        String billingAddress = firstMatch(ADDRESS_PATTERN, rawContent, 1);
        String reference = originalPermitReference(tables);
        int validHours = validHours(rawContent);

        return new SchedulePermit(
                normalizedPermitId,
                normalizedPermitId,
                permitNumber,
                "CHK",
                permitType,
                version,
                season,
                permitDate,
                operatorId.toUpperCase(Locale.ROOT),
                reference,
                validHours,
                billingAddress,
                "SC",
                rawContent,
                flights);
    }

    private List<List<List<String>>> tables(Range range) {
        List<List<List<String>>> result = new ArrayList<>();
        TableIterator iterator = new TableIterator(range);
        while (iterator.hasNext()) {
            Table table = iterator.next();
            List<List<String>> rows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                TableRow row = table.getRow(rowIndex);
                List<String> cells = new ArrayList<>();
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    TableCell cell = row.getCell(cellIndex);
                    cells.add(clean(cell.text()));
                }
                rows.add(cells);
            }
            result.add(rows);
        }
        return result;
    }

    private List<List<String>> findNewScheduleTable(List<List<List<String>>> tables) {
        return tables.stream()
                .filter(table -> !table.isEmpty())
                .filter(table -> columns(table.getFirst()).keySet().containsAll(SCHEDULE_HEADERS))
                .filter(table -> !columns(table.getFirst()).containsKey("originalpermit"))
                .findFirst()
                .orElse(null);
    }

    private List<ScheduleFlight> scheduleFlights(List<List<String>> table,
                                                 List<RouteRow> routes,
                                                 String fileName) {
        Map<String, Integer> columns = columns(table.getFirst());
        List<ScheduleFlight> flights = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            String flightNumber = value(row, columns, "flightnumber").toUpperCase(Locale.ROOT);
            if (flightNumber.isBlank()) {
                continue;
            }
            String aircraftType = value(row, columns, "aircrafttype").toUpperCase(Locale.ROOT);
            AircraftMapping aircraft = AIRCRAFT.get(aircraftType);
            if (aircraft == null) {
                throw invalid(fileName, "Unsupported aircraft type in new schedule: " + aircraftType);
            }
            String from = value(row, columns, "departureairport").toUpperCase(Locale.ROOT);
            String to = value(row, columns, "arrivalairport").toUpperCase(Locale.ROOT);
            String via = routes.stream()
                    .filter(route -> route.matches(from, to))
                    .map(RouteRow::airways)
                    .findFirst()
                    .orElse(null);
            if (via == null || via.isBlank()) {
                throw invalid(fileName, "Airways not found for new schedule route " + from + "-" + to);
            }
            flights.add(new ScheduleFlight(
                    "CAR",
                    aircraft.craftId(),
                    aircraft.mtow(),
                    flightNumber,
                    null,
                    normalizeDays(value(row, columns, "daysofservices"), fileName),
                    from,
                    to,
                    normalizeTime(value(row, columns, "etd")),
                    normalizeTime(value(row, columns, "eta")),
                    via,
                    parseDate(value(row, columns, "effectivefrom"), COMPACT_DATE, fileName, "effective-from date"),
                    parseDate(value(row, columns, "effectiveto"), COMPACT_DATE, fileName, "effective-to date"),
                    "CAR " + aircraftType));
        }
        return flights;
    }

    private List<RouteRow> routeRows(List<List<List<String>>> tables) {
        List<List<String>> routeTable = tables.stream()
                .filter(table -> !table.isEmpty())
                .filter(table -> columns(table.getFirst()).keySet().containsAll(Set.of("sector", "airways")))
                .findFirst()
                .orElse(null);
        if (routeTable == null) {
            return List.of();
        }
        Map<String, Integer> columns = columns(routeTable.getFirst());
        List<RouteRow> routes = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < routeTable.size(); rowIndex++) {
            List<String> row = routeTable.get(rowIndex);
            String sector = value(row, columns, "sector").toUpperCase(Locale.ROOT);
            String airways = normalizeAirways(value(row, columns, "airways"));
            Matcher matcher = Pattern.compile("^([A-Z]{3,4})\\s*[-–—]\\s*([A-Z]{3,4})$")
                    .matcher(sector);
            if (matcher.matches() && !airways.isBlank()) {
                routes.add(new RouteRow(matcher.group(1), matcher.group(2), airways));
            }
        }
        return routes;
    }

    private String originalPermitReference(List<List<List<String>>> tables) {
        for (List<List<String>> table : tables) {
            if (table.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = columns(table.getFirst());
            if (!columns.containsKey("originalpermit")) {
                continue;
            }
            for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
                String reference = value(table.get(rowIndex), columns, "originalpermit");
                if (!reference.isBlank()) {
                    return reference;
                }
            }
        }
        return null;
    }

    private int validHours(String rawContent) {
        String value = firstMatch(VALID_HOURS_PATTERN, rawContent, 1);
        return value == null ? 24 : Integer.parseInt(value);
    }

    private Map<String, Integer> columns(List<String> header) {
        return java.util.stream.IntStream.range(0, header.size())
                .boxed()
                .collect(Collectors.toMap(
                        index -> canonical(header.get(index)),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private String value(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.size() ? "" : clean(row.get(index));
    }

    private String normalizeDays(String raw, String fileName) {
        String compact = raw.replaceAll("\\s+", "");
        if (compact.length() != 7) {
            throw invalid(fileName, "Day-of-service value must contain seven positions: " + raw);
        }
        StringBuilder result = new StringBuilder(7);
        for (int index = 0; index < 7; index++) {
            char expected = (char) ('1' + index);
            result.append(compact.charAt(index) == expected ? expected : '0');
        }
        return result.toString();
    }

    private String normalizeAirways(String value) {
        return clean(value).toUpperCase(Locale.ROOT).replaceAll("\\s*[-–—]\\s*", "/");
    }

    private String normalizeTime(String value) {
        String normalized = value.replace(":", "").replaceAll("\\s+", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String canonical(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String clean(String value) {
        return value == null ? "" : value
                .replace('\u0007', ' ')
                .replace('\uFFFD', ' ')
                .replaceAll("[\\r\\n\\u000B]+", " ")
                .replaceAll("[\\p{Cc}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanMultiline(String value) {
        return value == null ? "" : value
                .replace('\u0007', ' ')
                .replace('\uFFFD', ' ')
                .replaceAll("[\\r\\n\\u000B]+", "\n")
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n+", "\n")
                .trim();
    }

    private LocalDate parseDate(String value,
                                DateTimeFormatter formatter,
                                String fileName,
                                String field) {
        try {
            return LocalDate.parse(value.trim(), formatter);
        } catch (DateTimeParseException exception) {
            throw invalid(fileName, "Invalid " + field + ": " + value);
        }
    }

    private Matcher require(Pattern pattern,
                            String text,
                            String fileName,
                            String message) {
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

    private record AircraftMapping(long craftId, BigDecimal mtow) {
    }
}
