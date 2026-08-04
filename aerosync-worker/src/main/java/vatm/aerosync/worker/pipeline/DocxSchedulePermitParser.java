package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
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
            "^([A-Z]{3,4})\\s*[-/\\u2013\\u2014]\\s*([A-Z]{3,4})$");
    private static final Pattern ICAO_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{3})(?![A-Z0-9])");
    private static final Pattern IATA_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{2})(?![A-Z0-9])");
    private static final Pattern CARRIER_NAME_PATTERN = Pattern.compile(
            "(?iu)(?:^|\\n)\\s*(?:\\d+(?:\\.\\d+)*\\.?\\s*)?"
                    + "(?:NAME|TÊN(?:\\s*/\\s*NAME)?|(?:CARRIER|OPERATOR)(?:\\s+NAME)?)"
                    + "\\s*:\\s*(?<value>.+?)"
                    + "(?=\\s+(?:IATA|ICAO|POSTAL|ADDRESS|ĐỊA\\s+CHỈ|TEL|PHONE|EMAIL)\\b|\\n|$)");
    private static final Pattern ICAO_FLIGHT_PREFIX_PATTERN = Pattern.compile(
            "^([A-Z]{3})(?=\\d)");
    private static final Pattern REPLACEMENT_SCHEDULE_CONTEXT = Pattern.compile(
            "(?iu)(?:LỊCH\\s+BAY.{0,50}(?:SỬA\\s+ĐỔI|THAY\\s+ĐỔI|ĐIỀU\\s+CHỈNH|BỔ\\s+SUNG|MỚI))"
                    + "|(?:(?:SỬA\\s+ĐỔI|THAY\\s+ĐỔI|ĐIỀU\\s+CHỈNH|BỔ\\s+SUNG).{0,50}LỊCH\\s+BAY)"
                    + "|(?:(?:NEW|REVISED|AMENDED|REPLACEMENT)\\s+(?:FLIGHT\\s+)?SCHEDULE)"
                    + "|(?:SCHEDULE\\s+(?:REVISION|CHANGES?|AMENDMENT))");
    private static final Pattern ORIGINAL_SCHEDULE_CONTEXT = Pattern.compile(
            "(?iu)(?:LỊCH\\s+BAY.{0,50}(?:DỰ\\s+KIẾN|GỐC|BAN\\s+ĐẦU))"
                    + "|(?:(?:DỰ\\s+KIẾN|GỐC|BAN\\s+ĐẦU).{0,50}LỊCH\\s+BAY)"
                    + "|(?:(?:OLD|ORIGINAL|PLANNED)\\s+(?:FLIGHT\\s+)?SCHEDULE)"
                    + "|(?:SCHEDULE\\s+(?:OLD|ORIGINAL|PLANNED))");
    private static final Map<String, List<String>> UNIVERSAL_COLUMN_ALIASES = Map.ofEntries(
            Map.entry("flightNumber", List.of("FlightNumber")),
            Map.entry("effectiveFrom", List.of("EffectiveFrom")),
            Map.entry("effectiveTo", List.of("EffectiveTo")),
            Map.entry("fromAirport", List.of(
                    "Dep airport", "Sân bay đi", "Sân bay điDeparture Airport")),
            Map.entry("etd", List.of(
                    "EOBT", "Giờ dự kiến đi", "Giờ dự kiến điEOBT")),
            Map.entry("toAirport", List.of(
                    "Arr airport", "Sân bay đến", "Sân bay đếnArrival Airport")),
            Map.entry("eta", List.of(
                    "EIBT", "Giờ dự kiến đến", "Giờ dựkiến đếnEIBT")),
            Map.entry("sector", List.of("Setors", "Chặng baySector")),
            Map.entry("airways", List.of("ATS Routes", "Đường hàng khôngAirways")),
            Map.entry("aircraftType", List.of(
                    "Aircraft type", "Loại tàu bay/Aircraft Type(mã ICAO/ ICAO code)")));

    private static final DateTimeFormatter ORACLE_TIME = DateTimeFormatter.ofPattern("HHmm");

    private final WordPermitDocumentReader documentReader;
    private final WordPermitFormatDetector formatDetector;
    private final AirportCodeCatalog airportCodeCatalog;
    private final PermitOperatorCatalog permitOperatorCatalog;
    private final PermitOperatorResolver permitOperatorResolver;
    private final Clock clock;

    /**
     * Retained for callers that constructed the former DOCX-only parser directly.
     */
    public DocxSchedulePermitParser() {
        this(new WordPermitDocumentReader(),
                new WordPermitFormatDetector(new DocxPermitProfileCatalog()),
                new AirportCodeCatalog(), new PermitOperatorCatalog(), Clock.systemDefaultZone());
    }

    public DocxSchedulePermitParser(WordPermitDocumentReader documentReader,
                                    WordPermitFormatDetector formatDetector) {
        this(documentReader, formatDetector, new AirportCodeCatalog(),
                new PermitOperatorCatalog(), Clock.systemDefaultZone());
    }

    @Autowired
    public DocxSchedulePermitParser(WordPermitDocumentReader documentReader,
                                    WordPermitFormatDetector formatDetector,
                                    AirportCodeCatalog airportCodeCatalog,
                                    PermitOperatorCatalog permitOperatorCatalog,
                                    Clock clock) {
        this(documentReader, formatDetector, airportCodeCatalog, permitOperatorCatalog,
                (iataCode, carrierName) -> java.util.Optional.ofNullable(
                        permitOperatorCatalog.operatorForIata(iataCode)),
                clock);
    }

    public DocxSchedulePermitParser(WordPermitDocumentReader documentReader,
                                    WordPermitFormatDetector formatDetector,
                                    AirportCodeCatalog airportCodeCatalog,
                                    PermitOperatorCatalog permitOperatorCatalog,
                                    PermitOperatorResolver permitOperatorResolver,
                                    Clock clock) {
        this.documentReader = documentReader;
        this.formatDetector = formatDetector;
        this.airportCodeCatalog = airportCodeCatalog;
        this.permitOperatorCatalog = permitOperatorCatalog;
        this.permitOperatorResolver = permitOperatorResolver;
        this.clock = clock;
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
        String billingAddress = extractText(profile.billingAddress(), document, fileName, "billing address");

        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        List<List<List<String>>> scheduleTables = selectScheduleTables(schedule, document);
        List<List<List<String>>> originalScheduleTables = selectOriginalScheduleTables(schedule, document);
        if (scheduleTables.isEmpty() || scheduleTables.getFirst().size() < 2) {
            throw invalid(fileName, missingScheduleFields(profile, document));
        }
        String iataCode = extractIataPrefix(document.rawContent());
        String carrierName = extractCarrierName(document);
        String operatorId = extractLabeledCode(document.rawContent(), ICAO_LABEL_PATTERN);
        if (operatorId == null && (iataCode == null || hasFixedValue(profile.operator()))) {
            operatorId = normalizedOperator(
                    extractOptionalText(profile.operator(), document, fileName, "carrier ICAO code"));
        }
        if (operatorId == null && iataCode != null) {
            operatorId = permitOperatorResolver.resolve(iataCode, carrierName).orElse(null);
        }
        if (operatorId == null && iataCode == null) {
            operatorId = inferOperator(scheduleTables, schedule.columns());
        }
        if (operatorId == null) {
            operatorId = "PRV";
        }
        String resolvedOperatorId = operatorId;
        String iataPrefix = schedule.inferIataPrefix()
                ? iataCode
                : null;
        boolean normalizeAirportsToIcao = profile.validation() == null
                || !profile.validation().allowIataAirports();
        List<RouteRow> routes = routeRows(
                profile.route(), document.tables(), normalizeAirportsToIcao);
        if (profile.route() != null && profile.route().tableRequired() && routes.isEmpty()) {
            throw invalid(fileName, "Airways table not found for profile " + profile.id());
        }

        String auxiliaryAircraftTypes = auxiliaryAircraftTypes(profile.aircraft(), document.tables());
        List<ScheduleFlight> flights = scheduleTables.stream()
                .flatMap(table -> scheduleFlights(
                        profile, table, routes, auxiliaryAircraftTypes,
                        document.rawContent(), resolvedOperatorId, iataPrefix,
                        normalizeAirportsToIcao, false, fileName).stream())
                .toList();
        List<ScheduleFlight> originalFlights = originalScheduleTables.stream()
                .flatMap(table -> scheduleFlights(
                        profile, table, routes, auxiliaryAircraftTypes,
                        document.rawContent(), resolvedOperatorId, iataPrefix,
                        normalizeAirportsToIcao, true, fileName).stream())
                .toList();
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
                resolvedOperatorId.toUpperCase(Locale.ROOT),
                reference,
                master.validHours(),
                billingAddress,
                master.flightType(),
                validation != null && validation.allowIataAirports(),
                profile.route() != null && profile.route().allowEmpty(),
                validation != null && validation.reviewOnly(),
                document.rawContent(),
                flights,
                originalFlights);
    }

    private String normalizedOperator(String value) {
        String normalized = value == null
                ? ""
                : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9]{3}") ? normalized : null;
    }

    private String inferOperator(List<List<List<String>>> scheduleTables,
                                 Map<String, List<String>> aliases) {
        for (List<List<String>> scheduleTable : scheduleTables) {
            Map<String, Integer> columns = resolveColumns(scheduleTable.getFirst(), aliases);
            for (int rowIndex = 1; rowIndex < scheduleTable.size(); rowIndex++) {
                String flightNumber = clean(value(scheduleTable.get(rowIndex), columns, "flightNumber"))
                        .replaceAll("[^A-Za-z0-9]", "")
                        .toUpperCase(Locale.ROOT);
                Matcher prefix = ICAO_FLIGHT_PREFIX_PATTERN.matcher(flightNumber);
                if (prefix.find()) {
                    return prefix.group(1);
                }
            }
        }
        return null;
    }

    private boolean hasFixedValue(DocxPermitFormatProfile.TextField field) {
        return field != null && field.fixedValue() != null && !field.fixedValue().isBlank();
    }

    private String extractIataPrefix(String rawContent) {
        return extractLabeledCode(rawContent, IATA_LABEL_PATTERN);
    }

    private String extractCarrierName(WordPermitDocument document) {
        for (List<List<String>> table : document.tables()) {
            for (List<String> row : table) {
                for (String cell : row) {
                    Matcher matcher = CARRIER_NAME_PATTERN.matcher("\n" + clean(cell));
                    if (matcher.find()) {
                        return clean(matcher.group("value"));
                    }
                }
            }
        }
        Matcher matcher = CARRIER_NAME_PATTERN.matcher("\n" + document.rawContent());
        return matcher.find() ? clean(matcher.group("value")) : null;
    }

    private String extractLabeledCode(String content, Pattern pattern) {
        String folded = Normalizer.normalize(
                        Objects.requireNonNullElse(content, "")
                                .replace('\u00A0', ' ')
                                .replace('Đ', 'D')
                                .replace('đ', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        Matcher matcher = pattern.matcher(folded);
        return matcher.find() ? matcher.group("value").toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeFlightNumber(String value,
                                         String operatorId,
                                         String iataPrefix) {
        String compact = clean(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (iataPrefix != null
                && compact.matches(Pattern.quote(iataPrefix) + "\\d.*")) {
            return operatorId + compact.substring(iataPrefix.length());
        }
        return compact;
    }

    private String purposeId(DocxPermitFormatProfile profile,
                             String rawContent,
                             String rawFrom,
                             String rawTo,
                             String rowHint) {
        DocxPermitFormatProfile.PurposeDefinition purpose = profile.purpose();
        if (purpose == null) {
            return profile.schedule().purposeId();
        }
        String routeDescription = routeDescription(rawContent, rawFrom, rawTo);
        String summary = purposeSummary(rawContent);
        for (String candidate : List.of(
                Objects.requireNonNullElse(rowHint, ""),
                Objects.requireNonNullElse(routeDescription, ""),
                Objects.requireNonNullElse(summary, ""),
                rawContent)) {
            if (candidate.isBlank()) {
                continue;
            }
            String match = safeList(purpose.mappings()).stream()
                    .filter(mapping -> Pattern.compile(mapping.pattern()).matcher(candidate).find())
                    .map(DocxPermitFormatProfile.PatternValue::value)
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match;
            }
        }
        return purpose.defaultId();
    }

    private String routeDescription(String rawContent, String from, String to) {
        if (from.isBlank() || to.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile(
                        "(?imu)^\\s*" + Pattern.quote(from)
                                + "\\s*[-\\u2013\\u2014]\\s*" + Pattern.quote(to)
                                + "\\s*:\\s*(?<value>[^\\n]+)")
                .matcher(rawContent);
        return matcher.find() ? matcher.group("value") : null;
    }

    private String purposeSummary(String rawContent) {
        Matcher matcher = Pattern.compile(
                "(?imu)^\\s*(?:\\d+(?:\\.\\d+)?\\.?\\s*)?"
                        + "(?:PURPOSE\\s+OF\\s+FLIGHT(?:\\(S\\))?|MỤC\\s+ĐÍCH\\s+CHUYẾN\\s+BAY)"
                        + "\\s*:\\s*(?<value>[^\\n]+)")
                .matcher(rawContent);
        return matcher.find() ? matcher.group("value") : null;
    }

    private List<ScheduleFlight> scheduleFlights(DocxPermitFormatProfile profile,
                                                 List<List<String>> table,
                                                 List<RouteRow> routes,
                                                 String auxiliaryAircraftTypes,
                                                 String rawContent,
                                                 String operatorId,
                                                 String iataPrefix,
                                                 boolean normalizeAirportsToIcao,
                                                 boolean allowMissingAircraft,
                                                 String fileName) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        Map<String, Integer> columns = resolveColumns(table.getFirst(), schedule.columns());
        int firstDataRow = 1;
        if (!columns.keySet().containsAll(safeList(schedule.requiredColumns()))
                && isHeaderlessScheduleTable(table, schedule)) {
            columns = new LinkedHashMap<>(safeMap(schedule.headerlessColumns()));
            firstDataRow = 0;
        }
        List<ScheduleFlight> flights = new ArrayList<>();
        for (int rowIndex = firstDataRow; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            if (isRepeatedScheduleHeader(row, columns)) {
                continue;
            }
            String flightNumber = normalizeFlightNumber(
                    value(row, columns, "flightNumber"), operatorId, iataPrefix);
            if (flightNumber.isBlank()) {
                continue;
            }
            if (isScheduleSectionLabel(row, columns)) {
                continue;
            }
            String rawFrom = value(row, columns, "fromAirport").toUpperCase(Locale.ROOT);
            String rawTo = value(row, columns, "toAirport").toUpperCase(Locale.ROOT);
            String from = normalizeAirport(rawFrom, normalizeAirportsToIcao);
            String to = normalizeAirport(rawTo, normalizeAirportsToIcao);
            if (from.equals(to) && routes.size() == 1) {
                RouteRow publishedRoute = routes.getFirst();
                if (publishedRoute.from().equals(from) && !publishedRoute.to().equals(to)) {
                    to = publishedRoute.to();
                } else if (publishedRoute.to().equals(to)
                        && !publishedRoute.from().equals(from)) {
                    from = publishedRoute.from();
                }
            }
            AircraftSource aircraftSource = aircraftType(
                    profile.aircraft(), row, columns, auxiliaryAircraftTypes);
            String aircraftType = aircraftSource.value();
            boolean mayInheritAircraft = allowMissingAircraft
                    || (profile.validation() != null && profile.validation().reviewOnly());
            if ((aircraftType == null || aircraftType.isBlank()) && !mayInheritAircraft) {
                throw invalid(fileName, "Aircraft type is required");
            }
            String purposeId = purposeId(
                    profile, rawContent, rawFrom, rawTo, value(row, columns, "remark"));
            String routeFrom = from;
            String routeTo = to;
            RouteRow matchedRoute = routes.stream()
                    .filter(route -> route.matches(routeFrom, routeTo))
                    .findFirst()
                    .orElse(null);
            if (profile.route() != null
                    && profile.route().filterSchedule()
                    && !routes.isEmpty()
                    && matchedRoute == null) {
                continue;
            }
            String via = matchedRoute == null
                    ? (profile.route() != null
                            && profile.route().fallbackToFirst()
                            && !routes.isEmpty()
                            ? routes.getFirst().airways()
                            : null)
                    : matchedRoute.airways();
            LocalDate beginDate = parseDate(
                    value(row, columns, "effectiveFrom"),
                    schedule.dateFormats(), schedule.locale(), fileName, "effective-from date");
            LocalDate endDate = parseDate(
                    value(row, columns, "effectiveTo"),
                    schedule.dateFormats(), schedule.locale(), fileName, "effective-to date");
            String serviceDays = normalizeDays(value(row, columns, "serviceDays"), fileName);
            if (beginDate.equals(endDate)) {
                // A one-day effective window uniquely determines the operating weekday,
                // even when the source form's day flag contains a clerical mismatch.
                serviceDays = serviceDayFor(beginDate);
            }
            flights.add(new ScheduleFlight(
                    purposeId,
                    0L,
                    null,
                    flightNumber,
                    null,
                    serviceDays,
                    from,
                    to,
                    normalizeTime(
                            value(row, columns, "etd"), schedule.timeFormats(), schedule.locale(),
                            fileName, "ETD", false),
                    schedule.includeEta() ? normalizeTime(
                            value(row, columns, "eta"), schedule.timeFormats(), schedule.locale(),
                            fileName, "ETA", true) : null,
                    via,
                    beginDate,
                    endDate,
                    remark(
                            profile.aircraft(),
                            purposeId,
                            aircraftSource.defaulted() ? null : aircraftType),
                    aircraftType));
        }
        return flights;
    }

    private List<RouteRow> routeRows(DocxPermitFormatProfile.RouteDefinition route,
                                     List<List<List<String>>> tables,
                                     boolean normalizeAirportsToIcao) {
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
                tables, List.of(), route.columns(), route.requiredColumns(), List.of(), List.of(),
                route.lastMatchingTable());
        if (table == null || table.size() < 2) {
            return routes;
        }
        Map<String, Integer> columns = resolveColumns(table.getFirst(), route.columns());
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            Matcher matcher = ROUTE_PATTERN.matcher(value(row, columns, "sector").toUpperCase(Locale.ROOT));
            String airways = normalizeAirways(value(row, columns, "airways"));
            if (matcher.matches() && (!airways.isBlank() || route.allowEmpty())) {
                routes.add(new RouteRow(
                        normalizeAirport(matcher.group(1), normalizeAirportsToIcao),
                        normalizeAirport(matcher.group(2), normalizeAirportsToIcao),
                        airways));
            }
        }
        return routes;
    }

    private String normalizeAirport(String value, boolean normalizeToIcao) {
        return normalizeToIcao
                ? airportCodeCatalog.normalize(value)
                : airportCodeCatalog.canonicalize(value);
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
                List.of(),
                aircraft.lastMatchingTable());
        if (table == null || table.size() < 2) {
            return null;
        }
        Map<String, Integer> columns = resolveColumns(table.getFirst(), aircraft.auxiliaryColumns());
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            String type = value(table.get(rowIndex), columns, aircraft.auxiliaryTypeColumn())
                    .toUpperCase(Locale.ROOT);
            if (!type.isBlank() && type.matches(".*[A-Z0-9].*")) {
                types.add(type);
            }
        }
        return types.isEmpty() ? null : String.join("/", types);
    }

    private AircraftSource aircraftType(DocxPermitFormatProfile.AircraftDefinition aircraft,
                                        List<String> row,
                                        Map<String, Integer> columns,
                                        String auxiliaryTypes) {
        if (aircraft != null
                && aircraft.scheduleColumn() != null
                && !aircraft.scheduleColumn().isBlank()) {
            String rowType = value(row, columns, aircraft.scheduleColumn()).toUpperCase(Locale.ROOT);
            if (!rowType.matches(".*[A-Z0-9].*")) {
                rowType = "";
                Integer configuredIndex = columns.get(aircraft.scheduleColumn());
                if (configuredIndex != null) {
                    for (int index = row.size() - 1; index > configuredIndex; index--) {
                        String candidate = clean(row.get(index)).toUpperCase(Locale.ROOT);
                        if (candidate.matches(".*[A-Z0-9].*")) {
                            rowType = candidate;
                            break;
                        }
                    }
                }
            }
            if (!rowType.isBlank()) {
                return new AircraftSource(rowType, false);
            }
        }
        if (auxiliaryTypes != null && !auxiliaryTypes.isBlank()) {
            return new AircraftSource(auxiliaryTypes, false);
        }
        return new AircraftSource(aircraft == null ? null : aircraft.defaultType(), true);
    }

    private String remark(DocxPermitFormatProfile.AircraftDefinition aircraft,
                          String purposeId,
                          String aircraftType) {
        String prefix = aircraft == null || aircraft.remarkPrefix() == null
                ? purposeId
                : aircraft.remarkPrefix().trim();
        if (prefix.isBlank()) {
            prefix = purposeId;
        }
        String type = aircraftType == null ? "" : aircraftType.trim();
        return (prefix + " " + type).trim();
    }

    private List<List<String>> findTable(List<List<List<String>>> tables,
                                         List<String> tableContexts,
                                         Map<String, List<String>> aliases,
                                         List<String> requiredColumns,
                                         List<String> excludedColumns,
                                         List<String> contextPatterns,
                                         boolean lastMatchingTable) {
        List<List<List<String>>> matches = findTables(
                tables, tableContexts, aliases, requiredColumns, excludedColumns, contextPatterns);
        if (matches.isEmpty()) {
            return null;
        }
        return lastMatchingTable ? matches.getLast() : matches.getFirst();
    }

    private List<List<List<String>>> selectScheduleTables(
            DocxPermitFormatProfile.ScheduleDefinition schedule,
            WordPermitDocument document) {
        List<TableMatch> structuralMatches = findTableMatches(
                document.tables(),
                document.tableContexts(),
                schedule.columns(),
                schedule.requiredColumns(),
                schedule.excludeColumns());
        if (!safeMap(schedule.headerlessColumns()).isEmpty()) {
            java.util.Set<Integer> matchedIndexes = structuralMatches.stream()
                    .map(TableMatch::index)
                    .collect(java.util.stream.Collectors.toSet());
            for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
                if (matchedIndexes.contains(tableIndex)) {
                    continue;
                }
                List<List<String>> table = document.tables().get(tableIndex);
                if (isHeaderlessScheduleTable(table, schedule)) {
                    String context = tableIndex < document.tableContexts().size()
                            ? document.tableContexts().get(tableIndex)
                            : "";
                    structuralMatches.add(new TableMatch(tableIndex, table, context));
                }
            }
            structuralMatches.sort(java.util.Comparator.comparingInt(TableMatch::index));
        }
        if (structuralMatches.isEmpty()) {
            return List.of();
        }

        ScheduleSection activeSection = ScheduleSection.UNKNOWN;
        List<ClassifiedTable> classified = new ArrayList<>();
        for (TableMatch match : structuralMatches) {
            String context = scheduleSectionText(match);
            if (ORIGINAL_SCHEDULE_CONTEXT.matcher(context).find()) {
                activeSection = ScheduleSection.ORIGINAL;
            } else if (matchesAny(context, schedule.preferredTableContextPatterns())
                    || REPLACEMENT_SCHEDULE_CONTEXT.matcher(context).find()) {
                activeSection = ScheduleSection.REPLACEMENT;
            }
            classified.add(new ClassifiedTable(match, activeSection));
        }

        List<TableMatch> selected = classified.stream()
                .filter(candidate -> candidate.section() == ScheduleSection.REPLACEMENT)
                .map(ClassifiedTable::match)
                .toList();
        if (selected.isEmpty()) {
            selected = structuralMatches.stream()
                    .filter(match -> matchesAll(match.context(), schedule.tableContextPatterns()))
                    .toList();
            if (schedule.lastMatchingTable() && !selected.isEmpty()) {
                selected = List.of(selected.getLast());
            }
        }

        if (!safeList(schedule.supplementalTableContextPatterns()).isEmpty()) {
            LinkedHashMap<Integer, TableMatch> withSupplemental = new LinkedHashMap<>();
            selected.forEach(match -> withSupplemental.put(match.index(), match));
            structuralMatches.stream()
                    .filter(match -> matchesAll(
                            match.context(), schedule.supplementalTableContextPatterns()))
                    .forEach(match -> withSupplemental.putIfAbsent(match.index(), match));
            selected = new ArrayList<>(withSupplemental.values());
        }
        return selected.stream().map(TableMatch::table).toList();
    }

    private List<List<List<String>>> selectOriginalScheduleTables(
            DocxPermitFormatProfile.ScheduleDefinition schedule,
            WordPermitDocument document) {
        List<TableMatch> structuralMatches = findTableMatches(
                document.tables(), document.tableContexts(), schedule.columns(),
                schedule.requiredColumns(), List.of());
        if (!safeMap(schedule.headerlessColumns()).isEmpty()) {
            java.util.Set<Integer> matchedIndexes = structuralMatches.stream()
                    .map(TableMatch::index)
                    .collect(java.util.stream.Collectors.toSet());
            for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
                if (matchedIndexes.contains(tableIndex)) {
                    continue;
                }
                List<List<String>> table = document.tables().get(tableIndex);
                if (isHeaderlessScheduleTable(table, schedule)) {
                    String context = tableIndex < document.tableContexts().size()
                            ? document.tableContexts().get(tableIndex) : "";
                    structuralMatches.add(new TableMatch(tableIndex, table, context));
                }
            }
            structuralMatches.sort(java.util.Comparator.comparingInt(TableMatch::index));
        }

        ScheduleSection activeSection = ScheduleSection.UNKNOWN;
        List<TableMatch> originals = new ArrayList<>();
        for (TableMatch match : structuralMatches) {
            String context = scheduleSectionText(match);
            if (ORIGINAL_SCHEDULE_CONTEXT.matcher(context).find()) {
                activeSection = ScheduleSection.ORIGINAL;
            } else if (matchesAny(context, schedule.preferredTableContextPatterns())
                    || REPLACEMENT_SCHEDULE_CONTEXT.matcher(context).find()) {
                activeSection = ScheduleSection.REPLACEMENT;
            }
            if (activeSection == ScheduleSection.ORIGINAL) {
                originals.add(match);
            }
        }
        return originals.stream().map(TableMatch::table).toList();
    }

    private String scheduleSectionText(TableMatch match) {
        StringBuilder text = new StringBuilder(match.context());
        for (int row = 0; row < Math.min(2, match.table().size()); row++) {
            text.append(' ').append(String.join(" ", match.table().get(row)));
        }
        return text.toString();
    }

    private List<TableMatch> findTableMatches(List<List<List<String>>> tables,
                                              List<String> tableContexts,
                                              Map<String, List<String>> aliases,
                                              List<String> requiredColumns,
                                              List<String> excludedColumns) {
        List<TableMatch> matches = new ArrayList<>();
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
            String context = tableIndex < tableContexts.size() ? tableContexts.get(tableIndex) : "";
            matches.add(new TableMatch(tableIndex, table, context));
        }
        return matches;
    }

    private boolean matchesAny(String context, List<String> patterns) {
        return safeList(patterns).stream()
                .anyMatch(pattern -> Pattern.compile(pattern).matcher(context).find());
    }

    private boolean matchesAll(String context, List<String> patterns) {
        return safeList(patterns).stream()
                .allMatch(pattern -> Pattern.compile(pattern).matcher(context).find());
    }

    private List<List<List<String>>> findTables(List<List<List<String>>> tables,
                                                List<String> tableContexts,
                                                Map<String, List<String>> aliases,
                                                List<String> requiredColumns,
                                                List<String> excludedColumns,
                                                List<String> contextPatterns) {
        List<List<List<String>>> matches = new ArrayList<>();
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
            matches.add(table);
        }
        return matches;
    }

    private Map<String, Integer> resolveColumns(List<String> header,
                                                Map<String, List<String>> aliases) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Map<String, List<String>> safeAliases = safeMap(aliases);
        for (int index = 0; index < header.size(); index++) {
            String actual = canonicalHeader(header.get(index));
            for (Map.Entry<String, List<String>> entry : safeAliases.entrySet()) {
                boolean matches = java.util.stream.Stream.concat(
                                safeList(entry.getValue()).stream(),
                                safeList(UNIVERSAL_COLUMN_ALIASES.get(entry.getKey())).stream())
                        .map(this::canonicalHeader)
                        .anyMatch(actual::equals);
                if (matches) {
                    result.putIfAbsent(entry.getKey(), index);
                }
            }
        }
        return result;
    }

    private boolean isScheduleSectionLabel(List<String> row, Map<String, Integer> columns) {
        return value(row, columns, "effectiveFrom").isBlank()
                && value(row, columns, "effectiveTo").isBlank()
                && value(row, columns, "serviceDays").isBlank()
                && value(row, columns, "fromAirport").isBlank()
                && value(row, columns, "toAirport").isBlank()
                && value(row, columns, "etd").isBlank();
    }

    private boolean isRepeatedScheduleHeader(List<String> row, Map<String, Integer> columns) {
        String flight = canonicalHeader(value(row, columns, "flightNumber"));
        String effectiveFrom = canonicalHeader(value(row, columns, "effectiveFrom"));
        String from = canonicalHeader(value(row, columns, "fromAirport"));
        String etd = canonicalHeader(value(row, columns, "etd"));
        String to = canonicalHeader(value(row, columns, "toAirport"));
        return flight.equals("flightnumber")
                || effectiveFrom.equals("effectivefrom")
                || effectiveFrom.contains("ddmmmyy")
                || from.equals("departureairport")
                || from.contains("iatacode")
                || etd.equals("eobt")
                || etd.equals("utc")
                || to.equals("arrivalairport");
    }

    private boolean isHeaderlessScheduleTable(
            List<List<String>> table,
            DocxPermitFormatProfile.ScheduleDefinition schedule) {
        Map<String, Integer> indexes = safeMap(schedule.headerlessColumns());
        if (table.isEmpty()
                || !indexes.keySet().containsAll(safeList(schedule.requiredColumns()))) {
            return false;
        }
        List<String> first = table.getFirst();
        String flight = value(first, indexes, "flightNumber").replaceAll("[^A-Za-z0-9]", "");
        String effectiveFrom = value(first, indexes, "effectiveFrom");
        String from = value(first, indexes, "fromAirport").replaceAll("[^A-Za-z]", "");
        String to = value(first, indexes, "toAirport").replaceAll("[^A-Za-z]", "");
        return flight.matches("(?i)[A-Z0-9]{3,10}")
                && effectiveFrom.matches("(?i).*[0-9].*")
                && from.matches("(?i)[A-Z]{3,4}")
                && to.matches("(?i)[A-Z]{3,4}");
    }

    private String missingScheduleFields(DocxPermitFormatProfile profile,
                                         WordPermitDocument document) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        List<String> closestMissing = null;
        for (List<List<String>> table : document.tables()) {
            if (table.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = resolveColumns(table.getFirst(), schedule.columns());
            List<String> missing = safeList(schedule.requiredColumns()).stream()
                    .filter(required -> !columns.containsKey(required))
                    .toList();
            if (closestMissing == null || missing.size() < closestMissing.size()) {
                closestMissing = missing;
            }
        }
        if (closestMissing == null) {
            return "Schedule table not found for profile " + profile.id()
                    + "; missing required fields: " + String.join(", ", schedule.requiredColumns());
        }
        if (closestMissing.isEmpty()) {
            return "Schedule table columns matched profile " + profile.id()
                    + ", but the required table section/context was not found";
        }
        String details = closestMissing.stream()
                .map(field -> field + " (accepted headers: "
                        + String.join(" | ", safeList(schedule.columns().get(field))) + ")")
                .collect(java.util.stream.Collectors.joining("; "));
        return "Schedule table not found for profile " + profile.id()
                + "; missing required fields: " + details;
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
        String dateSource = sourceText(field.source(), document);
        Matcher matcher = Pattern.compile(field.pattern()).matcher(dateSource);
        while (matcher.find()) {
            int valueStart = matcher.start(field.group());
            if (valueStart > 0 && Character.isDigit(dateSource.charAt(valueStart - 1))) {
                continue;
            }
            return parseDate(
                    requireGroup(matcher, field.group(), fileName, description),
                    field.formats(), field.locale(), fileName, description);
        }
        return LocalDate.now(clock);
    }

    private String extractText(DocxPermitFormatProfile.TextField field,
                               WordPermitDocument document,
                               String fileName,
                               String description) {
        return extractText(field, document, fileName, description, true);
    }

    private String extractOptionalText(DocxPermitFormatProfile.TextField field,
                                       WordPermitDocument document,
                                       String fileName,
                                       String description) {
        return extractText(field, document, fileName, description, false);
    }

    private String extractText(DocxPermitFormatProfile.TextField field,
                               WordPermitDocument document,
                               String fileName,
                               String description,
                               boolean enforceRequired) {
        if (field != null && field.fixedValue() != null && !field.fixedValue().isBlank()) {
            return clean(field.fixedValue());
        }
        if (field == null || field.pattern() == null || field.pattern().isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile(field.pattern()).matcher(sourceText(field.source(), document));
        if (!matcher.find()) {
            if (enforceRequired && field.required()) {
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

    private enum ScheduleSection {
        UNKNOWN,
        ORIGINAL,
        REPLACEMENT
    }

    private record TableMatch(int index, List<List<String>> table, String context) {
    }

    private record ClassifiedTable(TableMatch match, ScheduleSection section) {
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
        String normalizedValue = value.trim()
                .replaceFirst("(?iu)^(\\d{1,2})(?:ST|ND|RD|TH)(?=\\s)", "$1");
        List<String> acceptedFormats = new ArrayList<>(safeList(formats));
        List.of("dMMMyy", "d-MMM-yy", "d/M/uuuu", "d MMM uuuu")
                .forEach(pattern -> {
                    if (!acceptedFormats.contains(pattern)) {
                        acceptedFormats.add(pattern);
                    }
                });
        for (String pattern : acceptedFormats) {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .toFormatter(locale);
            try {
                return LocalDate.parse(normalizedValue, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next configured format.
            }
        }
        throw invalid(fileName, "Invalid " + field + ": " + value);
    }

    private String normalizeDays(String raw, String fileName) {
        String compact = raw.replace("\u2026", "...").replaceAll("\\s+", "");
        boolean[] activeDays = new boolean[7];
        for (int index = 0; index < compact.length(); index++) {
            char value = compact.charAt(index);
            if (value >= '1' && value <= '7') {
                activeDays[value - '1'] = true;
            }
        }
        StringBuilder result = new StringBuilder(7);
        for (int index = 0; index < activeDays.length; index++) {
            char expected = (char) ('1' + index);
            result.append(activeDays[index] ? expected : '0');
        }
        if (result.chars().allMatch(value -> value == '0')) {
            throw invalid(fileName, "Day-of-service value has no operating day: " + raw);
        }
        return result.toString();
    }

    private String serviceDayFor(LocalDate date) {
        char[] days = "0000000".toCharArray();
        int index = date.getDayOfWeek().getValue() - 1;
        days[index] = (char) ('1' + index);
        return new String(days);
    }

    private String normalizeAirways(String value) {
        return clean(value).toUpperCase(Locale.ROOT)
                .replaceAll("(?iu)\\s+OR\\s+", "/")
                .replaceAll("\\s*[,;]\\s*", "/")
                .replaceAll("\\s*[-\\u2013\\u2014]\\s*", "/")
                .replaceAll("/{2,}", "/");
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
        boolean nextDay = cleanValue.matches(".*\\+\\s*(?:1)?$");
        cleanValue = cleanValue.replaceFirst("\\s*\\+\\s*(?:1)?$", "");
        cleanValue = cleanValue.replaceFirst("(?iu)\\s*Z$", "");
        Locale locale = localeTag == null || localeTag.isBlank()
                ? Locale.ENGLISH
                : Locale.forLanguageTag(localeTag);
        for (String pattern : safeList(formats)) {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .toFormatter(locale);
            try {
                String normalized = java.time.LocalTime.parse(cleanValue, formatter).format(ORACLE_TIME);
                return nextDay ? normalized + "+" : normalized;
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

    private String canonicalHeader(String value) {
        return canonical(value).replaceFirst("\\d+$", "");
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

    private record AircraftSource(String value, boolean defaulted) {
    }
}
