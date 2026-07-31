package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LearnedPermitProfileReplayValidator {

    private static final Pattern DATE_TEXT = Pattern.compile(
            "(?iu)(?<![A-Z0-9])(?:\\d{1,2}[/-]\\d{1,2}[/-]20\\d{2}"
                    + "|\\d{1,2}[ -]?[A-Z]{3}[ -]?\\d{2,4})(?![A-Z0-9])");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            dateFormatter("d/M/uuuu"),
            dateFormatter("d-M-uuuu"),
            dateFormatter("dMMMyy"),
            dateFormatter("d-MMM-yy"),
            dateFormatter("d-MMM-uuuu"),
            dateFormatter("d MMM uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final AirportCodeCatalog airportCodeCatalog;

    public LearnedPermitProfileReplayValidator(
            AirportCodeCatalog airportCodeCatalog) {
        this.airportCodeCatalog = airportCodeCatalog;
    }

    public ReplayResult validate(
            CompiledPermitTrainingProfile profile,
            PermitTrainingDocument document,
            PermitReviewSnapshot expected) {
        Map<String, String> fields = extractFields(profile, document);
        List<TableExtraction> tables = extractTables(profile, document);
        List<String> errors = new ArrayList<>();
        validateFields(profile, fields, expected, errors);
        validateOptions(profile.options(), expected, errors);
        validateSchedule(profile, tables, expected, errors);
        validateRoutes(profile, tables, expected, errors);
        validateAircraft(tables, expected, errors);
        return new ReplayResult(
                errors.isEmpty(),
                Map.copyOf(fields),
                List.copyOf(tables),
                List.copyOf(errors));
    }

    static String findDateText(String selectedText, String confirmedValue) {
        LocalDate expected;
        try {
            expected = LocalDate.parse(confirmedValue);
        } catch (RuntimeException exception) {
            return null;
        }
        Matcher matcher = DATE_TEXT.matcher(selectedText);
        while (matcher.find()) {
            LocalDate candidate = parseDate(matcher.group());
            if (expected.equals(candidate)) {
                return matcher.group();
            }
        }
        return null;
    }

    private Map<String, String> extractFields(
            CompiledPermitTrainingProfile profile,
            PermitTrainingDocument document) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (CompiledPermitTrainingProfile.FieldBinding field
                : profile.fields()) {
            String value = switch (field.source()) {
                case CELL -> cellValue(document, field.cell());
                case TEXT -> textValue(document.rawContent(), field.text());
                case CONSTANT -> field.constantValue();
            };
            String normalized = normalizeField(field.semanticField(), value);
            if (field.source()
                    != PermitTrainingProfileDefinition.SourceKind.CONSTANT
                    && field.confirmedSampleValue() != null
                    && sampleValue(field, field.semanticField())
                    .equals(normalized)) {
                normalized = normalizeField(
                        field.semanticField(),
                        field.confirmedSampleValue());
            }
            fields.put(
                    field.semanticField(),
                    normalized);
        }
        return fields;
    }

    private String sampleValue(
            CompiledPermitTrainingProfile.FieldBinding field,
            String semanticField) {
        String sample = field.cell() == null
                ? field.text().sampleValue()
                : field.cell().sampleValue();
        return normalizeField(semanticField, sample);
    }

    private List<TableExtraction> extractTables(
            CompiledPermitTrainingProfile profile,
            PermitTrainingDocument document) {
        List<TableExtraction> result = new ArrayList<>();
        for (CompiledPermitTrainingProfile.TableBinding binding
                : profile.tables()) {
            PermitTrainingDocument.Table table = document.tables().stream()
                    .filter(candidate -> candidate.index()
                            == binding.tableIndex())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Training source is missing table "
                                    + binding.tableIndex()));
            List<Map<String, String>> rows = table.rows().stream()
                    .filter(row -> row.index() >= binding.dataStartRowIndex())
                    .map(row -> extractRow(row, binding.columns()))
                    .filter(row -> row.values().stream()
                            .anyMatch(this::hasText))
                    .toList();
            result.add(new TableExtraction(
                    binding.role(),
                    binding.tableIndex(),
                    rows));
        }
        return result;
    }

    private Map<String, String> extractRow(
            PermitTrainingDocument.Row row,
            Map<String, CompiledPermitTrainingProfile.ColumnBinding> columns) {
        Map<String, String> values = new LinkedHashMap<>();
        columns.forEach((semanticColumn, binding) -> values.put(
                semanticColumn,
                row.cells().stream()
                        .filter(cell -> cell.columnIndex()
                                == binding.columnIndex())
                        .findFirst()
                        .map(PermitTrainingDocument.Cell::value)
                        .map(String::trim)
                        .orElse("")));
        return Map.copyOf(values);
    }

    private void validateFields(
            CompiledPermitTrainingProfile profile,
            Map<String, String> actual,
            PermitReviewSnapshot expected,
            List<String> errors) {
        for (CompiledPermitTrainingProfile.FieldBinding field
                : profile.fields()) {
            String expectedValue = expectedField(
                    field.semanticField(), expected);
            if (expectedValue == null) {
                continue;
            }
            compare(
                    field.semanticField(),
                    expectedValue,
                    actual.get(field.semanticField()),
                    errors);
        }
    }

    private void validateSchedule(
            CompiledPermitTrainingProfile profile,
            List<TableExtraction> tables,
            PermitReviewSnapshot expected,
            List<String> errors) {
        List<Map<String, String>> rows = tables.stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.SCHEDULE
                        || table.role()
                        == PermitTrainingProfileDefinition.TableRole
                        .SUPPLEMENTAL_SCHEDULE)
                .flatMap(table -> table.rows().stream())
                .toList();
        if (rows.size() != expected.flights().size()) {
            errors.add("schedule.rowCount expected="
                    + expected.flights().size() + " actual=" + rows.size());
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            PermitReviewFlightSnapshot flight = expected.flights().get(index);
            for (Map.Entry<String, String> value : row.entrySet()) {
                String expectedValue = expectedScheduleValue(
                        value.getKey(), flight, profile.options());
                if (expectedValue != null) {
                    expectedValue = normalizeScheduleValue(
                            value.getKey(), expectedValue, profile.options());
                    compare(
                            "schedule[" + index + "]." + value.getKey(),
                            expectedValue,
                            normalizeScheduleValue(
                                    value.getKey(),
                                    value.getValue(),
                                    profile.options()),
                            errors);
                }
            }
        }
    }

    private void validateRoutes(
            CompiledPermitTrainingProfile profile,
            List<TableExtraction> tables,
            PermitReviewSnapshot expected,
            List<String> errors) {
        List<Map<String, String>> routes = tables.stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.ROUTE)
                .flatMap(table -> table.rows().stream())
                .toList();
        for (Map<String, String> route : routes) {
            String sector = route.get("sector");
            String airways = route.get("airways");
            if (!hasText(sector) || !hasText(airways)) {
                continue;
            }
            String[] airports = sector.toUpperCase(Locale.ROOT)
                    .split("\\s*[-–—]\\s*", 2);
            if (airports.length != 2) {
                errors.add("route.sector invalid=" + sector);
                continue;
            }
            String from = normalizeAirport(airports[0], profile.options());
            String to = normalizeAirport(airports[1], profile.options());
            PermitReviewFlightSnapshot flight = expected.flights().stream()
                    .filter(item -> Objects.equals(item.fromAirport(), from)
                            && Objects.equals(item.toAirport(), to))
                    .findFirst()
                    .orElse(null);
            if (flight == null) {
                errors.add("route.sector not found in expected schedule="
                        + sector);
            } else {
                compare(
                        "route[" + sector + "].airways",
                        clean(flight.via()),
                        clean(airways),
                        errors);
            }
        }
    }

    private void validateAircraft(
            List<TableExtraction> tables,
            PermitReviewSnapshot expected,
            List<String> errors) {
        List<Map<String, String>> aircraft = tables.stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.AIRCRAFT)
                .flatMap(table -> table.rows().stream())
                .toList();
        for (int index = 0; index < aircraft.size(); index++) {
            Map<String, String> row = aircraft.get(index);
            String type = clean(row.get("aircraftType"));
            if (hasText(type) && expected.flights().stream()
                    .map(PermitReviewFlightSnapshot::sourceAircraftType)
                    .map(this::clean)
                    .noneMatch(type::equalsIgnoreCase)) {
                errors.add("aircraft[" + index
                        + "].aircraftType was not found in expected flights");
            }
            String registration = clean(row.get("registrationMarks"));
            if (hasText(registration)
                    && expected.flights().stream()
                    .map(PermitReviewFlightSnapshot::registration)
                    .filter(this::hasText)
                    .map(this::clean)
                    .noneMatch(registration::equalsIgnoreCase)) {
                errors.add("aircraft[" + index
                        + "].registrationMarks was not found in expected flights");
            }
        }
    }

    private String expectedField(
            String semanticField,
            PermitReviewSnapshot expected) {
        return switch (semanticField) {
            case "permit.sourceNumber" -> clean(expected.sourcePermitNumber());
            case "permit.date" -> expected.permitDate() == null
                    ? null : expected.permitDate().toString();
            case "operator.icao" -> clean(expected.operatorId());
            case "operator.iata" -> null;
            case "billing.address" -> clean(expected.billingAddress());
            case "reference" -> clean(expected.reference());
            case "purpose" -> expected.flights().stream()
                    .map(PermitReviewFlightSnapshot::purposeId)
                    .filter(this::hasText)
                    .findFirst()
                    .map(this::clean)
                    .orElse(null);
            default -> null;
        };
    }

    private String expectedScheduleValue(
            String semanticColumn,
            PermitReviewFlightSnapshot flight,
            PermitTrainingProfileDefinition.Options options) {
        return switch (semanticColumn) {
            case "flightNumber" -> clean(flight.flightNumber());
            case "effectiveFrom" -> date(flight.beginDate());
            case "effectiveTo" -> date(flight.endDate());
            case "serviceDays" -> compact(flight.serviceDays());
            case "fromAirport" -> clean(flight.fromAirport());
            case "etd" -> time(flight.etd());
            case "toAirport" -> clean(flight.toAirport());
            case "eta" -> time(flight.eta());
            case "aircraftType" -> clean(flight.sourceAircraftType());
            case "originalPermit", "remark" -> clean(flight.remark());
            default -> null;
        };
    }

    private void validateOptions(
            PermitTrainingProfileDefinition.Options options,
            PermitReviewSnapshot expected,
            List<String> errors) {
        if (options == null) {
            errors.add("profile.options are missing");
            return;
        }
        compare("options.authorId", options.authorId(), expected.authorId(), errors);
        compare("options.permitType", options.permitType(), expected.permitType(), errors);
        compare("options.version", options.version(), expected.version(), errors);
        compare("options.season", options.season(), expected.season(), errors);
        compare("options.flightType", options.flightType(), expected.flightType(), errors);
        if (!Objects.equals(options.validHours(), expected.validHours())) {
            errors.add("options.validHours expected=" + expected.validHours()
                    + " actual=" + options.validHours());
        }
        if (options.allowIataAirports() != expected.iataAirportsAllowed()) {
            errors.add("options.allowIataAirports expected="
                    + expected.iataAirportsAllowed() + " actual="
                    + options.allowIataAirports());
        }
        if (options.emptyAirwaysAllowed() != expected.emptyAirwaysAllowed()) {
            errors.add("options.emptyAirwaysAllowed expected="
                    + expected.emptyAirwaysAllowed() + " actual="
                    + options.emptyAirwaysAllowed());
        }
    }

    private String normalizeField(String semanticField, String value) {
        String cleaned = clean(value);
        return switch (semanticField) {
            case "permit.date" -> date(parseRequiredDate(cleaned));
            case "operator.icao", "operator.iata", "purpose" ->
                    cleaned.toUpperCase(Locale.ROOT);
            default -> cleaned;
        };
    }

    private String normalizeScheduleValue(
            String semanticColumn,
            String value,
            PermitTrainingProfileDefinition.Options options) {
        String cleaned = clean(value);
        return switch (semanticColumn) {
            case "effectiveFrom", "effectiveTo" ->
                    date(parseRequiredDate(cleaned));
            case "serviceDays" -> compact(cleaned);
            case "fromAirport", "toAirport" ->
                    normalizeAirport(cleaned, options);
            case "etd", "eta" -> time(cleaned);
            case "flightNumber", "aircraftType" ->
                    compact(cleaned).toUpperCase(Locale.ROOT);
            default -> cleaned;
        };
    }

    private String normalizeAirport(
            String value,
            PermitTrainingProfileDefinition.Options options) {
        boolean allowIata = options != null && options.allowIataAirports();
        return allowIata
                ? airportCodeCatalog.canonicalize(value)
                : airportCodeCatalog.normalize(value);
    }

    private String cellValue(
            PermitTrainingDocument document,
            CompiledPermitTrainingProfile.CellLocator locator) {
        return document.tables().stream()
                .filter(table -> table.index() == locator.tableIndex())
                .flatMap(table -> table.rows().stream())
                .filter(row -> row.index() == locator.rowIndex())
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell.columnIndex() == locator.columnIndex())
                .findFirst()
                .map(PermitTrainingDocument.Cell::value)
                .orElseThrow(() -> new IllegalStateException(
                        "Training source is missing compiled cell "
                                + locator.tableIndex() + ":"
                                + locator.rowIndex() + ":"
                                + locator.columnIndex()));
    }

    private String textValue(
            String rawContent,
            CompiledPermitTrainingProfile.TextLocator locator) {
        List<String> matches = new ArrayList<>();
        for (String line : rawContent.split("\\R", -1)) {
            int start = locator.anchorBefore().isEmpty()
                    ? 0 : line.indexOf(locator.anchorBefore());
            if (start < 0) {
                continue;
            }
            start += locator.anchorBefore().length();
            int end = locator.anchorAfter().isEmpty()
                    ? line.length()
                    : line.indexOf(locator.anchorAfter(), start);
            if (end >= start) {
                String value = line.substring(start, end).trim();
                if (!value.isBlank()) {
                    matches.add(value);
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Text locator expected one match but found "
                            + matches.size());
        }
        return matches.getFirst();
    }

    private void compare(
            String field,
            String expected,
            String actual,
            List<String> errors) {
        if (!Objects.equals(clean(expected), clean(actual))) {
            errors.add(field + " expected=" + safe(expected)
                    + " actual=" + safe(actual));
        }
    }

    private static LocalDate parseDate(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported, fixed date format.
            }
        }
        return null;
    }

    private static DateTimeFormatter dateFormatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    private LocalDate parseRequiredDate(String value) {
        LocalDate result = parseDate(value);
        if (result == null) {
            throw new IllegalStateException(
                    "Unsupported learned-profile date: " + safe(value));
        }
        return result;
    }

    private String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private String time(String value) {
        String compact = compact(value);
        return compact == null ? null : compact.replace(":", "");
    }

    private String compact(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.replaceAll("\\s+", "");
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() <= 120
                ? value
                : value.substring(0, 120);
    }

    public record ReplayResult(
            boolean passed,
            Map<String, String> fields,
            List<TableExtraction> tables,
            List<String> errors
    ) {
    }

    public record TableExtraction(
            PermitTrainingProfileDefinition.TableRole role,
            int tableIndex,
            List<Map<String, String>> rows
    ) {
    }
}
