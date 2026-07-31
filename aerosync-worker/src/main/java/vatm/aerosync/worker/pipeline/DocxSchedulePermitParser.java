package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.PermitFieldDiagnostic;
import vatm.aerosync.worker.model.PermitParseWarning;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
    private static final Pattern ICAO_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "\\s*:\\s*(?<value>[A-Z0-9]{3})(?![A-Z0-9])");
    private static final Pattern IATA_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "\\s*:\\s*(?<value>[A-Z0-9]{2})(?![A-Z0-9])");
    private static final Pattern ICAO_FLIGHT_PREFIX_PATTERN = Pattern.compile(
            "^([A-Z]{3})(?=\\d)");

    private static final DateTimeFormatter ORACLE_TIME = DateTimeFormatter.ofPattern("HHmm");

    private final WordPermitDocumentReader documentReader;
    private final WordPermitFormatDetector formatDetector;
    private final AirportCodeCatalog airportCodeCatalog;

    /**
     * Retained for callers that constructed the former DOCX-only parser directly.
     */
    public DocxSchedulePermitParser() {
        this(new WordPermitDocumentReader(),
                new WordPermitFormatDetector(new DocxPermitProfileCatalog()),
                new AirportCodeCatalog());
    }

    public DocxSchedulePermitParser(WordPermitDocumentReader documentReader,
                                    WordPermitFormatDetector formatDetector) {
        this(documentReader, formatDetector, new AirportCodeCatalog());
    }

    @Autowired
    public DocxSchedulePermitParser(WordPermitDocumentReader documentReader,
                                    WordPermitFormatDetector formatDetector,
                                    AirportCodeCatalog airportCodeCatalog) {
        this.documentReader = documentReader;
        this.formatDetector = formatDetector;
        this.airportCodeCatalog = airportCodeCatalog;
    }

    public SchedulePermit parse(Path file, String fileName) {
        return parseWithDiagnostics(file, fileName).permit();
    }

    public WordPermitParseResult parseWithDiagnostics(Path file, String fileName) {
        try {
            return parseWithDiagnostics(documentReader.read(file), fileName);
        } catch (FormatValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid(fileName, "Failed to parse Word permit: " + exception.getMessage());
        }
    }

    SchedulePermit parse(WordPermitDocument document, String fileName) {
        return parseWithDiagnostics(document, fileName).permit();
    }

    public WordPermitParseResult parseWithTrainingCandidate(
            Path file,
            String fileName,
            PermitTrainingCandidate candidate) {
        try {
            WordPermitDocument document = documentReader.read(file);
            WordPermitDetectionResult detection = formatDetector.detectResult(
                    document, fileName, candidate);
            return parseWithDiagnostics(document, fileName, detection);
        } catch (FormatValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid(
                    fileName,
                    "Failed to validate training candidate: "
                            + exception.getMessage());
        }
    }

    WordPermitParseResult parseWithDiagnostics(WordPermitDocument document, String fileName) {
        WordPermitDetectionResult detection = formatDetector.detectResult(document, fileName);
        return parseWithDiagnostics(document, fileName, detection);
    }

    private WordPermitParseResult parseWithDiagnostics(
            WordPermitDocument document,
            String fileName,
            WordPermitDetectionResult detection) {
        ParseDiagnostics diagnostics = new ParseDiagnostics(detection.warnings());
        SchedulePermit permit = parseProfile(
                detection.profile(),
                detection.declaredProfile(),
                detection.reviewRequired(),
                detection.semantics(),
                document,
                fileName,
                diagnostics);
        return new WordPermitParseResult(
                permit,
                detection.profile().id(),
                detection.profile().profileVersion(),
                detection.confidence(),
                detection.runnerUpMargin(),
                permit.reviewOnly() || diagnostics.reviewRequired(),
                detection.candidates(),
                diagnostics.fields(),
                diagnostics.warnings());
    }

    private SchedulePermit parseProfile(DocxPermitFormatProfile profile,
                                        DocxPermitFormatProfile declaredProfile,
                                        boolean detectionRequiresReview,
                                        PermitSemanticEvidence semantics,
                                        WordPermitDocument document,
                                        String fileName,
                                        ParseDiagnostics diagnostics) {
        PermitMatch permitMatch = permitMatch(profile, semantics, document, fileName);
        Matcher permitMatcher = permitMatch.matcher();
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
        diagnostics.field(
                "permitNumber",
                permitMatch.confidence(),
                permitMatch.source(),
                permitMatch.semantic() ? "SEMANTIC_HEADER" : "PROFILE_REGEX");
        if (permitMatch.semantic() && permitMatch.confidence() < 0.95) {
            diagnostics.warning(
                    "PERMIT_IDENTITY_LOW_CONTEXT",
                    "Permit identity was selected from " + permitMatch.source(),
                    true);
        }
        DateResolution dateResolution = resolvePermitDate(
                profile.permitDate(), semantics, document, fileName);
        LocalDate permitDate = dateResolution.value();
        diagnostics.field(
                "permitDate",
                dateResolution.confidence(),
                dateResolution.source(),
                dateResolution.method());
        if (dateResolution.confidence() < 0.90) {
            diagnostics.warning(
                    "PERMIT_DATE_LOW_CONFIDENCE",
                    "Permit date used " + dateResolution.method(),
                    true);
        }
        TextResolution billingResolution = resolveBillingAddress(
                profile.billingAddress(), semantics, document, fileName);
        String billingAddress = billingResolution == null ? null : billingResolution.value();
        if (billingResolution != null) {
            diagnostics.field(
                    "billingAddress",
                    billingResolution.confidence(),
                    billingResolution.source(),
                    billingResolution.method());
        }

        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        DocxPermitFormatProfile.ScheduleDefinition declaredSchedule = declaredProfile.schedule();
        List<WordPermitTableMatcher.TableMatch> structuralScheduleTables = findTables(
                document.tables(),
                document.tableContexts(),
                schedule.columns(),
                declaredSchedule.columns(),
                schedule.requiredColumns(),
                schedule.excludeColumns(),
                List.of());
        WordPermitTableMatcher.TableMatch scheduleTable = null;
        if (!safeList(schedule.preferredTableContextPatterns()).isEmpty()) {
            scheduleTable = findTable(
                    document.tables(),
                    document.tableContexts(),
                    schedule.columns(),
                    declaredSchedule.columns(),
                    schedule.requiredColumns(),
                    schedule.excludeColumns(),
                    schedule.preferredTableContextPatterns(),
                    schedule.lastMatchingTable());
        }
        if (scheduleTable == null && !safeList(schedule.tableContextPatterns()).isEmpty()) {
            scheduleTable = findTable(
                    document.tables(),
                    document.tableContexts(),
                    schedule.columns(),
                    declaredSchedule.columns(),
                    schedule.requiredColumns(),
                    schedule.excludeColumns(),
                    schedule.tableContextPatterns(),
                    schedule.lastMatchingTable());
        }
        if (scheduleTable == null) {
            scheduleTable = selectSemanticScheduleTable(
                    structuralScheduleTables,
                    semantics,
                    PermitSemanticEvidence.TableRole.REPLACEMENT,
                    schedule.lastMatchingTable());
            if (scheduleTable != null) {
                recordSemanticTableRole(
                        diagnostics,
                        "schedule",
                        scheduleTable,
                        semantics,
                        true);
            }
        }
        if (scheduleTable == null && !structuralScheduleTables.isEmpty()) {
            scheduleTable = schedule.lastMatchingTable()
                    ? structuralScheduleTables.getLast()
                    : structuralScheduleTables.getFirst();
        }
        if (scheduleTable == null || scheduleTable.dataRows().isEmpty()) {
            throw invalid(fileName, "Schedule table not found for profile " + profile.id());
        }
        diagnostics.table("schedule", scheduleTable);
        WordPermitTableMatcher.TableMatch primaryScheduleTable = scheduleTable;
        List<WordPermitTableMatcher.TableMatch> scheduleTables = new ArrayList<>();
        scheduleTables.add(primaryScheduleTable);
        selectSemanticScheduleTables(
                structuralScheduleTables,
                semantics,
                PermitSemanticEvidence.TableRole.SUPPLEMENTAL)
                .stream()
                .filter(table -> table.tableIndex() != primaryScheduleTable.tableIndex())
                .forEach(table -> {
                    recordSemanticTableRole(
                            diagnostics,
                            "schedule",
                            table,
                            semantics,
                            true);
                    scheduleTables.add(table);
                });
        if (!safeList(schedule.supplementalTableContextPatterns()).isEmpty()) {
            findTables(
                    document.tables(),
                    document.tableContexts(),
                    schedule.columns(),
                    declaredSchedule.columns(),
                    schedule.requiredColumns(),
                    schedule.excludeColumns(),
                    schedule.supplementalTableContextPatterns())
                    .stream()
                    .filter(table -> scheduleTables.stream().noneMatch(
                            existing -> existing.tableIndex() == table.tableIndex()))
                    .forEach(table -> {
                        diagnostics.table("schedule", table);
                        scheduleTables.add(table);
                    });
        }

        TextResolution operatorResolution = resolveOperator(
                profile.operator(), semantics, document, fileName);
        String configuredOperator = normalizedOperator(
                operatorResolution == null ? null : operatorResolution.value());
        String operatorId = configuredOperator;
        if (operatorId != null) {
            diagnostics.field(
                    "operator",
                    operatorResolution.confidence(),
                    operatorResolution.source(),
                    operatorResolution.method());
        }
        if (operatorId == null) {
            operatorId = inferOperator(scheduleTable, fileName);
            diagnostics.field(
                    "operator",
                    Math.min(0.90, scheduleTable.minimumConfidence()),
                    scheduleTable.source(),
                    "SCHEDULE_INFERENCE");
            diagnostics.warning(
                    "OPERATOR_INFERRED",
                    "Carrier ICAO code was inferred from a flight number",
                    false);
        }
        String resolvedOperatorId = operatorId;
        String iataPrefix = schedule.inferIataPrefix()
                ? semantics.operatorIata() == null
                        ? extractIataPrefix(document.rawContent())
                        : semantics.operatorIata().value()
                : null;
        boolean normalizeAirportsToIcao = profile.validation() == null
                || !profile.validation().allowIataAirports();
        List<RouteRow> routes = routeRows(
                profile.route(),
                declaredProfile.route(),
                document.tables(),
                normalizeAirportsToIcao,
                diagnostics);
        if (profile.route() != null && profile.route().tableRequired() && routes.isEmpty()) {
            throw invalid(fileName, "Airways table not found for profile " + profile.id());
        }

        String auxiliaryAircraftTypes = auxiliaryAircraftTypes(
                profile.aircraft(),
                declaredProfile.aircraft(),
                document.tables(),
                diagnostics);
        List<ScheduleFlight> flights = scheduleTables.stream()
                .flatMap(table -> scheduleFlights(
                        profile, table, routes, auxiliaryAircraftTypes,
                        document.rawContent(), resolvedOperatorId, iataPrefix,
                        normalizeAirportsToIcao, fileName).stream())
                .toList();
        if (flights.isEmpty()) {
            throw invalid(fileName, "No schedule rows found for profile " + profile.id());
        }

        String reference = extractText(profile.reference(), document, fileName, "permit reference");
        if ((reference == null || reference.isBlank())
                && profile.referenceColumn() != null
                && !profile.referenceColumn().isBlank()) {
            reference = joinedColumnValues(
                    document.tables(),
                    profile.schedule().columns(),
                    declaredProfile.schedule().columns(),
                    profile.referenceColumn());
        }

        DocxPermitFormatProfile.MasterDefaults master = profile.master();
        String sourcePermitNumber = profile.permit().sourceTemplate() == null
                || profile.permit().sourceTemplate().isBlank()
                ? permitMatch.sourcePermitNumber()
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
                detectionRequiresReview
                        || diagnostics.reviewRequired()
                        || validation != null && validation.reviewOnly(),
                document.rawContent(),
                flights);
    }

    private PermitMatch permitMatch(DocxPermitFormatProfile profile,
                                    PermitSemanticEvidence semantics,
                                    WordPermitDocument document,
                                    String fileName) {
        Pattern pattern = Pattern.compile(profile.permit().pattern());
        for (PermitSemanticEvidence.PermitIdentityCandidate candidate
                : semantics.permitIdentities()) {
            Matcher matcher = pattern.matcher(candidate.canonicalValue());
            if (matcher.find()) {
                return new PermitMatch(
                        matcher,
                        candidate.rawValue().toUpperCase(Locale.ROOT),
                        candidate.source(),
                        candidate.confidence(),
                        true);
            }
        }
        Matcher matcher = require(
                profile.permit().pattern(),
                document.rawContent(),
                fileName,
                "Permit number not found for profile " + profile.id());
        return new PermitMatch(
                matcher,
                matcher.group().toUpperCase(Locale.ROOT),
                "RAW",
                1.0,
                false);
    }

    private DateResolution resolvePermitDate(DocxPermitFormatProfile.DateField field,
                                             PermitSemanticEvidence semantics,
                                             WordPermitDocument document,
                                             String fileName) {
        PermitSemanticEvidence.SemanticValue<LocalDate> semanticDate =
                semantics.permitDate();
        if (semanticDate != null
                && semanticDate.confidence() >= 0.90
                && !"DOCUMENT_CREATED_DATE".equals(semanticDate.method())) {
            return new DateResolution(
                    semanticDate.value(),
                    semanticDate.source(),
                    semanticDate.confidence(),
                    semanticDate.method());
        }
        try {
            LocalDate configured = extractDate(field, document, fileName, "permit date");
            return new DateResolution(
                    configured,
                    field.source() == null ? "RAW" : field.source(),
                    1.0,
                    "PROFILE_REGEX_OR_METADATA");
        } catch (FormatValidationException exception) {
            if (semanticDate == null
                    || "DOCUMENT_CREATED_DATE".equals(semanticDate.method())
                    && !field.fallbackToDocumentCreatedDate()) {
                throw exception;
            }
            return new DateResolution(
                    semanticDate.value(),
                    semanticDate.source(),
                    semanticDate.confidence(),
                    semanticDate.method());
        }
    }

    private TextResolution resolveOperator(DocxPermitFormatProfile.TextField field,
                                           PermitSemanticEvidence semantics,
                                           WordPermitDocument document,
                                           String fileName) {
        String configured = extractTextIfPresent(
                field, document, fileName, "carrier ICAO code");
        if (configured != null) {
            return new TextResolution(
                    configured,
                    field.source() == null ? "RAW" : field.source(),
                    1.0,
                    "PROFILE_OVERRIDE");
        }
        PermitSemanticEvidence.SemanticValue<String> semanticOperator =
                semantics.operatorIcao();
        if (semanticOperator != null) {
            return new TextResolution(
                    applyValueMapping(field, semanticOperator.value()),
                    semanticOperator.source(),
                    semanticOperator.confidence(),
                    semanticOperator.method());
        }
        extractText(field, document, fileName, "carrier ICAO code");
        String shared = extractLabeledCode(document.rawContent(), ICAO_LABEL_PATTERN);
        return shared == null
                ? null
                : new TextResolution(shared, "RAW", 0.95, "SHARED_LABEL");
    }

    private TextResolution resolveBillingAddress(DocxPermitFormatProfile.TextField field,
                                                 PermitSemanticEvidence semantics,
                                                 WordPermitDocument document,
                                                 String fileName) {
        String configured = extractTextIfPresent(
                field, document, fileName, "billing address");
        if (configured != null) {
            return new TextResolution(
                    configured,
                    field.source() == null ? "RAW" : field.source(),
                    1.0,
                    "PROFILE_OVERRIDE");
        }
        PermitSemanticEvidence.SemanticValue<String> semanticAddress =
                semantics.billingAddress();
        if (semanticAddress != null) {
            return new TextResolution(
                    applyValueMapping(field, semanticAddress.value()),
                    semanticAddress.source(),
                    semanticAddress.confidence(),
                    semanticAddress.method());
        }
        extractText(field, document, fileName, "billing address");
        return null;
    }

    private String applyValueMapping(DocxPermitFormatProfile.TextField field,
                                     String value) {
        if (field == null) {
            return value;
        }
        return safeMap(field.valueMappings()).entrySet().stream()
                .filter(entry -> canonical(entry.getKey()).equals(canonical(value)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(value);
    }

    private WordPermitTableMatcher.TableMatch selectSemanticScheduleTable(
            List<WordPermitTableMatcher.TableMatch> tables,
            PermitSemanticEvidence semantics,
            PermitSemanticEvidence.TableRole role,
            boolean lastMatchingTable) {
        List<WordPermitTableMatcher.TableMatch> matches =
                selectSemanticScheduleTables(tables, semantics, role);
        if (matches.isEmpty()) {
            return null;
        }
        return lastMatchingTable ? matches.getLast() : matches.getFirst();
    }

    private List<WordPermitTableMatcher.TableMatch> selectSemanticScheduleTables(
            List<WordPermitTableMatcher.TableMatch> tables,
            PermitSemanticEvidence semantics,
            PermitSemanticEvidence.TableRole role) {
        return tables.stream()
                .filter(table -> {
                    PermitSemanticEvidence.TableRoleEvidence evidence =
                            semantics.tableRole(table.tableIndex());
                    return evidence != null && evidence.role() == role;
                })
                .toList();
    }

    private void recordSemanticTableRole(
            ParseDiagnostics diagnostics,
            String section,
            WordPermitTableMatcher.TableMatch table,
            PermitSemanticEvidence semantics,
            boolean reviewRequired) {
        PermitSemanticEvidence.TableRoleEvidence evidence =
                semantics.tableRole(table.tableIndex());
        if (evidence == null) {
            return;
        }
        diagnostics.field(
                section + ".tableRole",
                evidence.confidence(),
                evidence.source(),
                "SEMANTIC_" + evidence.role().name());
        diagnostics.warning(
                "SEMANTIC_TABLE_ROLE",
                "%s table %d was classified as %s".formatted(
                        section,
                        table.tableIndex() + 1,
                        evidence.role()),
                reviewRequired);
    }

    private String normalizedOperator(String value) {
        String normalized = value == null
                ? ""
                : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9]{3}") ? normalized : null;
    }

    private String inferOperator(WordPermitTableMatcher.TableMatch scheduleTable,
                                 String fileName) {
        Map<String, Integer> columns = scheduleTable.columns();
        for (List<String> row : scheduleTable.dataRows()) {
            String flightNumber = clean(value(row, columns, "flightNumber"))
                    .replaceAll("[^A-Za-z0-9]", "")
                    .toUpperCase(Locale.ROOT);
            Matcher prefix = ICAO_FLIGHT_PREFIX_PATTERN.matcher(flightNumber);
            if (prefix.find()) {
                return prefix.group(1);
            }
        }
        throw invalid(fileName, "Carrier ICAO code could not be inferred from the schedule");
    }

    private String extractIataPrefix(String rawContent) {
        return extractLabeledCode(rawContent, IATA_LABEL_PATTERN);
    }

    private String extractLabeledCode(String content, Pattern pattern) {
        String folded = Normalizer.normalize(
                        clean(content).replace('Đ', 'D').replace('đ', 'd'),
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
                                                 WordPermitTableMatcher.TableMatch table,
                                                 List<RouteRow> routes,
                                                 String auxiliaryAircraftTypes,
                                                 String rawContent,
                                                 String operatorId,
                                                 String iataPrefix,
                                                 boolean normalizeAirportsToIcao,
                                                 String fileName) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        Map<String, Integer> columns = table.columns();
        List<ScheduleFlight> flights = new ArrayList<>();
        for (List<String> row : table.dataRows()) {
            String flightNumber = normalizeFlightNumber(
                    value(row, columns, "flightNumber"), operatorId, iataPrefix);
            if (flightNumber.isBlank()) {
                continue;
            }
            String rawFrom = value(row, columns, "fromAirport").toUpperCase(Locale.ROOT);
            String rawTo = value(row, columns, "toAirport").toUpperCase(Locale.ROOT);
            String from = normalizeAirport(rawFrom, normalizeAirportsToIcao);
            String to = normalizeAirport(rawTo, normalizeAirportsToIcao);
            AircraftSource aircraftSource = aircraftType(
                    profile.aircraft(), row, columns, auxiliaryAircraftTypes);
            String aircraftType = aircraftSource.value();
            if (aircraftType == null || aircraftType.isBlank()) {
                throw invalid(fileName, "Aircraft type is required");
            }
            String purposeId = purposeId(
                    profile, rawContent, rawFrom, rawTo, value(row, columns, "remark"));
            RouteRow matchedRoute = routes.stream()
                    .filter(route -> route.matches(from, to))
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
                                     DocxPermitFormatProfile.RouteDefinition declaredRoute,
                                     List<List<List<String>>> tables,
                                     boolean normalizeAirportsToIcao,
                                     ParseDiagnostics diagnostics) {
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
        WordPermitTableMatcher.TableMatch table = findTable(
                tables,
                List.of(),
                route.columns(),
                declaredRoute == null ? route.columns() : declaredRoute.columns(),
                route.requiredColumns(),
                List.of(),
                List.of(),
                route.lastMatchingTable());
        if (table == null || table.dataRows().isEmpty()) {
            return routes;
        }
        diagnostics.table("route", table);
        Map<String, Integer> columns = table.columns();
        for (List<String> row : table.dataRows()) {
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

    private String auxiliaryAircraftTypes(
            DocxPermitFormatProfile.AircraftDefinition aircraft,
            DocxPermitFormatProfile.AircraftDefinition declaredAircraft,
            List<List<List<String>>> tables,
            ParseDiagnostics diagnostics) {
        if (aircraft == null || safeMap(aircraft.auxiliaryColumns()).isEmpty()) {
            return null;
        }
        WordPermitTableMatcher.TableMatch table = findTable(
                tables,
                List.of(),
                aircraft.auxiliaryColumns(),
                declaredAircraft == null
                        ? aircraft.auxiliaryColumns()
                        : declaredAircraft.auxiliaryColumns(),
                aircraft.auxiliaryRequiredColumns(),
                List.of(),
                List.of(),
                aircraft.lastMatchingTable());
        if (table == null || table.dataRows().isEmpty()) {
            return null;
        }
        diagnostics.table("aircraft", table);
        Map<String, Integer> columns = table.columns();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (List<String> row : table.dataRows()) {
            String type = value(row, columns, aircraft.auxiliaryTypeColumn())
                    .toUpperCase(Locale.ROOT);
            if (!type.isBlank()) {
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
            if (rowType.isBlank()) {
                Integer configuredIndex = columns.get(aircraft.scheduleColumn());
                if (configuredIndex != null) {
                    for (int index = row.size() - 1; index > configuredIndex; index--) {
                        String candidate = clean(row.get(index)).toUpperCase(Locale.ROOT);
                        if (!candidate.isBlank()) {
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

    private WordPermitTableMatcher.TableMatch findTable(
            List<List<List<String>>> tables,
            List<String> tableContexts,
            Map<String, List<String>> aliases,
            Map<String, List<String>> declaredAliases,
            List<String> requiredColumns,
            List<String> excludedColumns,
            List<String> contextPatterns,
            boolean lastMatchingTable) {
        return WordPermitTableMatcher.find(
                tables,
                tableContexts,
                aliases,
                declaredAliases,
                requiredColumns,
                excludedColumns,
                contextPatterns,
                lastMatchingTable);
    }

    private List<WordPermitTableMatcher.TableMatch> findTables(
            List<List<List<String>>> tables,
            List<String> tableContexts,
            Map<String, List<String>> aliases,
            Map<String, List<String>> declaredAliases,
            List<String> requiredColumns,
            List<String> excludedColumns,
            List<String> contextPatterns) {
        return WordPermitTableMatcher.findAll(
                tables,
                tableContexts,
                aliases,
                declaredAliases,
                requiredColumns,
                excludedColumns,
                contextPatterns);
    }

    private String joinedColumnValues(List<List<List<String>>> tables,
                                      Map<String, List<String>> aliases,
                                      Map<String, List<String>> declaredAliases,
                                      String semanticColumn) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        List<WordPermitTableMatcher.TableMatch> matches = findTables(
                tables,
                List.of(),
                aliases,
                declaredAliases,
                List.of(semanticColumn),
                List.of(),
                List.of());
        for (WordPermitTableMatcher.TableMatch table : matches) {
            for (List<String> row : table.dataRows()) {
                String candidate = value(row, table.columns(), semanticColumn);
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
        Matcher matcher = Pattern.compile(field.pattern())
                .matcher(sourceText(field.source(), document));
        if (!matcher.find()) {
            if (field.fallbackToDocumentCreatedDate() && document.authoredDate() != null) {
                return document.authoredDate();
            }
            throw invalid(fileName, description + " not found");
        }
        return parseDate(
                requireGroup(matcher, field.group(), fileName, description),
                field.formats(), field.locale(), fileName, description);
    }

    private String extractText(DocxPermitFormatProfile.TextField field,
                               WordPermitDocument document,
                               String fileName,
                               String description) {
        return extractText(field, document, fileName, description, true);
    }

    private String extractTextIfPresent(DocxPermitFormatProfile.TextField field,
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

    private String clean(String value) {
        return PermitTextNormalizer.clean(value);
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

    private static final class ParseDiagnostics {

        private final List<PermitFieldDiagnostic> fields = new ArrayList<>();
        private final List<PermitParseWarning> warnings = new ArrayList<>();
        private final LinkedHashSet<String> warningKeys = new LinkedHashSet<>();

        private ParseDiagnostics(List<PermitParseWarning> initialWarnings) {
            initialWarnings.forEach(warning ->
                    warning(warning.code(), warning.message(), warning.reviewRequired()));
        }

        private void field(String field,
                           double confidence,
                           String source,
                           String method) {
            field(field, confidence, source, method, null);
        }

        private void field(String field,
                           double confidence,
                           String source,
                           String method,
                           String observedValue) {
            fields.add(new PermitFieldDiagnostic(
                    field, confidence, source, method, observedValue));
        }

        private void table(String section, WordPermitTableMatcher.TableMatch table) {
            table.columnMatches().forEach((semantic, match) -> field(
                    section + "." + semantic,
                    match.confidence(),
                    table.source() + ".COLUMN[" + (match.column() + 1) + "]",
                    match.kind().name(),
                    match.header()));
            if (table.headerRows() > 1) {
                warning(
                        "MULTI_ROW_HEADER",
                        "%s table %d used %d header rows".formatted(
                                section, table.tableIndex() + 1, table.headerRows()),
                        true);
            }
            table.columnMatches().forEach((semantic, match) -> {
                if (match.kind() == WordPermitTableMatcher.MatchKind.SHARED_ALIAS) {
                    warning(
                            "SHARED_ALIAS_USED",
                            "%s.%s matched shared alias '%s'".formatted(
                                    section, semantic, match.header()),
                            true);
                } else if (match.kind() == WordPermitTableMatcher.MatchKind.FUZZY_ALIAS) {
                    warning(
                            "FUZZY_ALIAS_USED",
                            "%s.%s fuzzily matched header '%s'".formatted(
                                    section, semantic, match.header()),
                            true);
                }
            });
        }

        private void warning(String code, String message, boolean reviewRequired) {
            String key = code + "|" + message;
            if (warningKeys.add(key)) {
                warnings.add(new PermitParseWarning(code, message, reviewRequired));
            }
        }

        private boolean reviewRequired() {
            return warnings.stream().anyMatch(PermitParseWarning::reviewRequired);
        }

        private List<PermitFieldDiagnostic> fields() {
            return List.copyOf(fields);
        }

        private List<PermitParseWarning> warnings() {
            return List.copyOf(warnings);
        }
    }

    private record RouteRow(String from, String to, String airways) {
        boolean matches(String candidateFrom, String candidateTo) {
            return from.equals(candidateFrom) && to.equals(candidateTo);
        }
    }

    private record AircraftSource(String value, boolean defaulted) {
    }

    private record PermitMatch(
            Matcher matcher,
            String sourcePermitNumber,
            String source,
            double confidence,
            boolean semantic
    ) {
    }

    private record DateResolution(
            LocalDate value,
            String source,
            double confidence,
            String method
    ) {
    }

    private record TextResolution(
            String value,
            String source,
            double confidence,
            String method
    ) {
    }
}
