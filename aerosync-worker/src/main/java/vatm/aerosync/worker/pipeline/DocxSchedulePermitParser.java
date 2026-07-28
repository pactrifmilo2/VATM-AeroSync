package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocxSchedulePermitParser {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "^([A-Z]{3,4})\\s*[-\\u2013\\u2014]\\s*([A-Z]{3,4})$");

    private static final DateTimeFormatter ORACLE_TIME = DateTimeFormatter.ofPattern("HHmm");

    private final WordPermitDocumentReader documentReader;
    private final WordPermitFormatDetector formatDetector;

    /**
     * Retained for callers that constructed the former DOCX-only parser directly.
     */
    public DocxSchedulePermitParser() {
        this(new WordPermitDocumentReader(),
                new WordPermitFormatDetector(new DocxPermitProfileCatalog()));
    }

    @Autowired
    public DocxSchedulePermitParser(WordPermitDocumentReader documentReader,
                                    WordPermitFormatDetector formatDetector) {
        this.documentReader = documentReader;
        this.formatDetector = formatDetector;
    }

    public SchedulePermit parse(Path file, String fileName) {
        try {
            return parse(documentReader.read(file), fileName);
        } catch (FormatValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid(fileName, "Failed to parse Word permit: " + exception.getMessage());
        }
    }

    SchedulePermit parse(WordPermitDocument document, String fileName) {
        return parseProfile(formatDetector.detect(document, fileName), document, fileName);
    }

    private SchedulePermit parseProfile(DocxPermitFormatProfile profile,
                                        WordPermitDocument document,
                                        String fileName) {
        Matcher permitMatcher = require(
                profile.permit().pattern(), document.rawContent(), fileName,
                "Permit number not found for profile " + profile.id());
        String permitNumber = profile.permit().numberTemplate() == null
                || profile.permit().numberTemplate().isBlank()
                ? requireGroup(
                        permitMatcher, profile.permit().numberGroup(), fileName, "permit number")
                : expandTemplate(
                        profile.permit().numberTemplate(), permitMatcher,
                        safeMap(profile.permit().zeroPadGroups()), fileName);
        String normalizedPermitId = expandTemplate(
                profile.permit().normalizedTemplate(),
                permitMatcher,
                safeMap(profile.permit().zeroPadGroups()),
                fileName);
        LocalDate permitDate = extractDate(profile.permitDate(), document, fileName, "permit date");
        String operatorId = extractText(profile.operator(), document, fileName, "carrier ICAO code");
        String billingAddress = extractText(profile.billingAddress(), document, fileName, "billing address");

        List<List<String>> scheduleTable = findTable(
                document.tables(),
                document.tableContexts(),
                profile.schedule().columns(),
                profile.schedule().requiredColumns(),
                profile.schedule().excludeColumns(),
                profile.schedule().tableContextPatterns());
        if (scheduleTable == null || scheduleTable.size() < 2) {
            throw invalid(fileName, "Schedule table not found for profile " + profile.id());
        }

        List<RouteRow> routes = routeRows(profile.route(), document.tables());
        if (profile.route() != null && profile.route().tableRequired() && routes.isEmpty()) {
            throw invalid(fileName, "Airways table not found for profile " + profile.id());
        }

        String auxiliaryAircraftTypes = auxiliaryAircraftTypes(profile.aircraft(), document.tables());
        List<ScheduleFlight> flights = scheduleFlights(
                profile, scheduleTable, routes, auxiliaryAircraftTypes, fileName);
        if (flights.isEmpty()) {
            throw invalid(fileName, "No schedule rows found for profile " + profile.id());
        }

        String reference = extractText(profile.reference(), document, fileName, "permit reference");
        if ((reference == null || reference.isBlank())
                && profile.referenceColumn() != null
                && !profile.referenceColumn().isBlank()) {
            reference = joinedColumnValues(
                    document.tables(), profile.schedule().columns(), profile.referenceColumn());
        }

        DocxPermitFormatProfile.MasterDefaults master = profile.master();
        String sourcePermitNumber = profile.permit().sourceTemplate() == null
                || profile.permit().sourceTemplate().isBlank()
                ? permitMatcher.group().toUpperCase(Locale.ROOT)
                : expandTemplate(
                        profile.permit().sourceTemplate(), permitMatcher,
                        safeMap(profile.permit().zeroPadGroups()), fileName);
        DocxPermitFormatProfile.ValidationRules validation = profile.validation();
        return new SchedulePermit(
                sourcePermitNumber,
                normalizedPermitId,
                permitNumber,
                master.authorId(),
                master.permitType(),
                master.version(),
                master.season(),
                permitDate,
                operatorId.toUpperCase(Locale.ROOT),
                reference,
                master.validHours(),
                billingAddress,
                master.flightType(),
                validation != null && validation.allowIataAirports(),
                profile.route() != null && profile.route().allowEmpty(),
                document.rawContent(),
                flights);
    }

    private List<ScheduleFlight> scheduleFlights(DocxPermitFormatProfile profile,
                                                 List<List<String>> table,
                                                 List<RouteRow> routes,
                                                 String auxiliaryAircraftTypes,
                                                 String fileName) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        Map<String, Integer> columns = resolveColumns(table.getFirst(), schedule.columns());
        List<ScheduleFlight> flights = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            String flightNumber = value(row, columns, "flightNumber").toUpperCase(Locale.ROOT);
            if (flightNumber.isBlank()) {
                continue;
            }
            String from = value(row, columns, "fromAirport").toUpperCase(Locale.ROOT);
            String to = value(row, columns, "toAirport").toUpperCase(Locale.ROOT);
            String aircraftType = aircraftType(profile.aircraft(), row, columns, auxiliaryAircraftTypes);
            DocxPermitFormatProfile.AircraftMapping aircraft = resolveAircraft(
                    profile.aircraft(), aircraftType, fileName);
            String via = routes.stream()
                    .filter(route -> route.matches(from, to))
                    .map(RouteRow::airways)
                    .findFirst()
                    .orElseGet(() -> profile.route() != null
                            && profile.route().fallbackToFirst()
                            && !routes.isEmpty()
                            ? routes.getFirst().airways()
                            : null);
            flights.add(new ScheduleFlight(
                    schedule.purposeId(),
                    aircraft.craftId(),
                    aircraft.mtow(),
                    flightNumber,
                    null,
                    normalizeDays(value(row, columns, "serviceDays"), fileName),
                    from,
                    to,
                    normalizeTime(
                            value(row, columns, "etd"), schedule.timeFormats(), schedule.locale(),
                            fileName, "ETD", false),
                    schedule.includeEta() ? normalizeTime(
                            value(row, columns, "eta"), schedule.timeFormats(), schedule.locale(),
                            fileName, "ETA", true) : null,
                    via,
                    parseDate(
                            value(row, columns, "effectiveFrom"),
                            schedule.dateFormats(), schedule.locale(), fileName, "effective-from date"),
                    parseDate(
                            value(row, columns, "effectiveTo"),
                            schedule.dateFormats(), schedule.locale(), fileName, "effective-to date"),
                    remark(profile.aircraft(), aircraftType)));
        }
        return flights;
    }

    private List<RouteRow> routeRows(DocxPermitFormatProfile.RouteDefinition route,
                                     List<List<List<String>>> tables) {
        if (route == null) {
            return List.of();
        }
        List<RouteRow> routes = new ArrayList<>();
        safeMap(route.staticAirways()).forEach((sector, airways) -> {
            Matcher matcher = ROUTE_PATTERN.matcher(sector.toUpperCase(Locale.ROOT));
            if (matcher.matches()) {
                routes.add(new RouteRow(
                        matcher.group(1), matcher.group(2), normalizeAirways(airways)));
            }
        });
        List<List<String>> table = findTable(
                tables, List.of(), route.columns(), route.requiredColumns(), List.of(), List.of());
        if (table == null || table.size() < 2) {
            return routes;
        }
        Map<String, Integer> columns = resolveColumns(table.getFirst(), route.columns());
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            Matcher matcher = ROUTE_PATTERN.matcher(value(row, columns, "sector").toUpperCase(Locale.ROOT));
            String airways = normalizeAirways(value(row, columns, "airways"));
            if (matcher.matches() && (!airways.isBlank() || route.allowEmpty())) {
                routes.add(new RouteRow(matcher.group(1), matcher.group(2), airways));
            }
        }
        return routes;
    }

    private String auxiliaryAircraftTypes(DocxPermitFormatProfile.AircraftDefinition aircraft,
                                           List<List<List<String>>> tables) {
        if (aircraft == null || safeMap(aircraft.auxiliaryColumns()).isEmpty()) {
            return null;
        }
        List<List<String>> table = findTable(
                tables,
                List.of(),
                aircraft.auxiliaryColumns(),
                aircraft.auxiliaryRequiredColumns(),
                List.of(),
                List.of());
        if (table == null || table.size() < 2) {
            return null;
        }
        Map<String, Integer> columns = resolveColumns(table.getFirst(), aircraft.auxiliaryColumns());
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            String type = value(table.get(rowIndex), columns, aircraft.auxiliaryTypeColumn())
                    .toUpperCase(Locale.ROOT);
            if (!type.isBlank()) {
                types.add(type);
            }
        }
        return types.isEmpty() ? null : String.join("/", types);
    }

    private String aircraftType(DocxPermitFormatProfile.AircraftDefinition aircraft,
                                List<String> row,
                                Map<String, Integer> columns,
                                String auxiliaryTypes) {
        if (aircraft != null
                && aircraft.scheduleColumn() != null
                && !aircraft.scheduleColumn().isBlank()) {
            String rowType = value(row, columns, aircraft.scheduleColumn()).toUpperCase(Locale.ROOT);
            if (!rowType.isBlank()) {
                return rowType;
            }
        }
        return auxiliaryTypes;
    }

    private DocxPermitFormatProfile.AircraftMapping resolveAircraft(
            DocxPermitFormatProfile.AircraftDefinition definition,
            String aircraftType,
            String fileName) {
        if (definition == null) {
            throw invalid(fileName, "Aircraft mapping definition is missing");
        }
        List<DocxPermitFormatProfile.AircraftMapping> mappings = safeList(definition.mappings());
        if (aircraftType != null && !aircraftType.isBlank()) {
            String key = canonical(aircraftType);
            DocxPermitFormatProfile.AircraftMapping mapped = mappings.stream()
                    .filter(mapping -> safeList(mapping.aliases()).stream()
                            .map(this::canonical)
                            .anyMatch(key::equals))
                    .findFirst()
                    .orElse(null);
            if (mapped != null) {
                return mapped;
            }
            if (definition.defaultCraftId() == null) {
                throw invalid(fileName, "Unsupported aircraft type: " + aircraftType);
            }
        }
        if (definition.defaultCraftId() == null) {
            throw invalid(fileName, "Aircraft type is required");
        }
        return new DocxPermitFormatProfile.AircraftMapping(
                List.of(), definition.defaultCraftId(),
                Objects.requireNonNullElse(definition.defaultMtow(), BigDecimal.ZERO));
    }

    private String remark(DocxPermitFormatProfile.AircraftDefinition aircraft, String aircraftType) {
        String prefix = aircraft == null || aircraft.remarkPrefix() == null
                ? ""
                : aircraft.remarkPrefix().trim();
        String type = aircraftType == null ? "" : aircraftType.trim();
        return (prefix + " " + type).trim();
    }

    private List<List<String>> findTable(List<List<List<String>>> tables,
                                         List<String> tableContexts,
                                         Map<String, List<String>> aliases,
                                         List<String> requiredColumns,
                                         List<String> excludedColumns,
                                         List<String> contextPatterns) {
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            List<List<String>> table = tables.get(tableIndex);
            if (table.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = resolveColumns(table.getFirst(), aliases);
            if (!columns.keySet().containsAll(safeList(requiredColumns))
                    || safeList(excludedColumns).stream().anyMatch(columns::containsKey)) {
                continue;
            }
            if (!safeList(contextPatterns).isEmpty()) {
                String context = tableIndex < tableContexts.size() ? tableContexts.get(tableIndex) : "";
                boolean contextMatches = safeList(contextPatterns).stream()
                        .allMatch(pattern -> Pattern.compile(pattern).matcher(context).find());
                if (!contextMatches) {
                    continue;
                }
            }
            return table;
        }
        return null;
    }

    private Map<String, Integer> resolveColumns(List<String> header,
                                                Map<String, List<String>> aliases) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Map<String, List<String>> safeAliases = safeMap(aliases);
        for (int index = 0; index < header.size(); index++) {
            String actual = canonical(header.get(index));
            for (Map.Entry<String, List<String>> entry : safeAliases.entrySet()) {
                boolean matches = safeList(entry.getValue()).stream()
                        .map(this::canonical)
                        .anyMatch(actual::equals);
                if (matches) {
                    result.putIfAbsent(entry.getKey(), index);
                }
            }
        }
        return result;
    }

    private String joinedColumnValues(List<List<List<String>>> tables,
                                      Map<String, List<String>> aliases,
                                      String semanticColumn) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (List<List<String>> table : tables) {
            if (table.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = resolveColumns(table.getFirst(), aliases);
            if (!columns.containsKey(semanticColumn)) {
                continue;
            }
            for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
                String candidate = value(table.get(rowIndex), columns, semanticColumn);
                if (!candidate.isBlank()) {
                    values.add(candidate);
                }
            }
        }
        return values.isEmpty() ? null : String.join("; ", values);
    }

    private LocalDate extractDate(DocxPermitFormatProfile.DateField field,
                                  WordPermitDocument document,
                                  String fileName,
                                  String description) {
        if (field == null) {
            throw invalid(fileName, description + " extraction is not configured");
        }
        Matcher matcher = require(
                field.pattern(), sourceText(field.source(), document), fileName,
                description + " not found");
        return parseDate(
                requireGroup(matcher, field.group(), fileName, description),
                field.formats(), field.locale(), fileName, description);
    }

    private String extractText(DocxPermitFormatProfile.TextField field,
                               WordPermitDocument document,
                               String fileName,
                               String description) {
        if (field == null || field.pattern() == null || field.pattern().isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile(field.pattern()).matcher(sourceText(field.source(), document));
        if (!matcher.find()) {
            if (field.required()) {
                throw invalid(fileName, description + " not found");
            }
            return null;
        }
        String extracted = clean(requireGroup(matcher, field.group(), fileName, description));
        return safeMap(field.valueMappings()).entrySet().stream()
                .filter(entry -> canonical(entry.getKey()).equals(canonical(extracted)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(extracted);
    }

    private String sourceText(String source, WordPermitDocument document) {
        return switch (source == null ? "RAW" : source.toUpperCase(Locale.ROOT)) {
            case "PARAGRAPH" -> document.paragraphText();
            case "TABLE" -> document.tableText();
            case "RAW" -> document.rawContent();
            default -> throw new IllegalArgumentException("Unknown text source: " + source);
        };
    }

    private String expandTemplate(String template,
                                  Matcher matcher,
                                  Map<String, Integer> zeroPadGroups,
                                  String fileName) {
        Matcher tokens = TEMPLATE_TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (tokens.find()) {
            String name = tokens.group(1);
            String value = requireGroup(matcher, name, fileName, "permit identity " + name);
            int width = zeroPadGroups.getOrDefault(name, 0);
            if (width > 0 && value.matches("\\d+")) {
                value = "%0" + width + "d";
                value = value.formatted(Integer.parseInt(requireGroup(
                        matcher, name, fileName, "permit identity " + name)));
            }
            tokens.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        tokens.appendTail(result);
        return result.toString().toUpperCase(Locale.ROOT);
    }

    private String requireGroup(Matcher matcher, String group, String fileName, String description) {
        try {
            String value = matcher.group(group);
            if (value == null || value.isBlank()) {
                throw invalid(fileName, description + " is blank");
            }
            return clean(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(fileName, "Profile references unknown regex group " + group);
        }
    }

    private Matcher require(String pattern, String text, String fileName, String message) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        if (!matcher.find()) {
            throw invalid(fileName, message);
        }
        return matcher;
    }

    private LocalDate parseDate(String value,
                                List<String> formats,
                                String localeTag,
                                String fileName,
                                String field) {
        Locale locale = localeTag == null || localeTag.isBlank()
                ? Locale.ENGLISH
                : Locale.forLanguageTag(localeTag);
        for (String pattern : safeList(formats)) {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .toFormatter(locale);
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next configured format.
            }
        }
        throw invalid(fileName, "Invalid " + field + ": " + value);
    }

    private String normalizeDays(String raw, String fileName) {
        String compact = raw.replace("\u2026", "...").replaceAll("\\s+", "");
        if (compact.length() != 7) {
            throw invalid(fileName, "Day-of-service value must contain seven positions: " + raw);
        }
        boolean[] activeDays = new boolean[7];
        for (int index = 0; index < compact.length(); index++) {
            char value = compact.charAt(index);
            if (value >= '1' && value <= '7') {
                activeDays[value - '1'] = true;
            }
        }
        StringBuilder result = new StringBuilder(7);
        for (int index = 0; index < 7; index++) {
            char expected = (char) ('1' + index);
            result.append(activeDays[index] ? expected : '0');
        }
        return result.toString();
    }

    private String normalizeAirways(String value) {
        return clean(value).toUpperCase(Locale.ROOT)
                .replaceAll("\\s*[-\\u2013\\u2014]\\s*", "/");
    }

    private String normalizeTime(String value,
                                 List<String> formats,
                                 String localeTag,
                                 String fileName,
                                 String field,
                                 boolean nullable) {
        String cleanValue = clean(value);
        if (cleanValue.isBlank() && nullable) {
            return null;
        }
        Locale locale = localeTag == null || localeTag.isBlank()
                ? Locale.ENGLISH
                : Locale.forLanguageTag(localeTag);
        for (String pattern : safeList(formats)) {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .toFormatter(locale);
            try {
                return java.time.LocalTime.parse(cleanValue, formatter).format(ORACLE_TIME);
            } catch (DateTimeParseException ignored) {
                // Try the next configured format.
            }
        }
        throw invalid(fileName, "Invalid " + field + ": " + value);
    }

    private String value(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.size() ? "" : clean(row.get(index));
    }

    private String canonical(String value) {
        String folded = Normalizer.normalize(
                        clean(value).replace('Đ', 'D').replace('đ', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
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
