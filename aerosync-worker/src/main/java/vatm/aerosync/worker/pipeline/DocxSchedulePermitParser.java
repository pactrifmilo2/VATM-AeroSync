package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.atfm.AtfmPermitIdNormalizer;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocxSchedulePermitParser {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)(\\?)?}");
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "^([A-Z]{3,4})\\s*[-/\\u2013\\u2014]\\s*([A-Z]{3,4})$");
    private static final Pattern GROUPED_ROUTE_PATTERN = Pattern.compile(
            "^([A-Z]{3,4}(?:\\s*/\\s*[A-Z]{3,4})*)\\s*[-\\u2013\\u2014]\\s*"
                    + "([A-Z]{3,4}(?:\\s*/\\s*[A-Z]{3,4})*)$");
    private static final Pattern SLASH_ROUTE_PATTERN = Pattern.compile(
            "^([A-Z]{3,4})\\s*/\\s*"
                    + "([A-Z]{3,4}(?:\\s*,\\s*[A-Z]{3,4})*)$");
    private static final Pattern ICAO_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{3})(?![A-Z0-9])");
    private static final Pattern IATA_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{2})(?![A-Z0-9])");
    private static final Pattern IATA_LABEL_LINE_PATTERN = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:");
    private static final Pattern ICAO_LABEL_LINE_PATTERN = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:");
    private static final Pattern IATA_NA_PATTERN = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*N[ \\t]*/?[ \\t]*A(?![A-Z0-9])");
    private static final Pattern ICAO_NA_PATTERN = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*N[ \\t]*/?[ \\t]*A(?![A-Z0-9])");
    private static final Pattern EMPTY_OPERATOR_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|ICAO\\s*(?:CODE)?|MA\\s*(?:IATA|ICAO))"
                    + "(?:\\s*\\([^)]*\\))?[ \\t]*:[ \\t]*(?=\\||\\r?$)",
            Pattern.MULTILINE);
    private static final Pattern CARRIER_NAME_PATTERN = Pattern.compile(
            "(?iu)(?:^|\\n)\\s*(?:\\d+(?:\\.\\d+)*\\.?\\s*)?"
                    + "(?:NAME|TÊN(?:\\s+HÃNG\\s+HÀNG\\s+KHÔNG)?(?:\\s*/\\s*NAME)?|"
                    + "NGƯỜI\\s+KHAI\\s+THÁC|(?:CARRIER|OPERATOR)(?:\\s+NAME)?)"
                    + "\\s*:\\s*(?<value>.+?)"
                    + "(?=\\s+(?:IATA|ICAO|POSTAL|ADDRESS|ĐỊA\\s+CHỈ|TEL|PHONE|EMAIL)\\b|\\n|$)");
    private static final Pattern ICAO_FLIGHT_PREFIX_PATTERN = Pattern.compile(
            "^([A-Z]{2,3})(?=\\d)");
    private static final Pattern CARRIER_NAME_LABEL_PATTERN = Pattern.compile(
            "(?iu)^(?:\\d+(?:\\.\\d+)*\\.?\\s*)?"
                    + "(?:NAME|TÊN(?:\\s+HÃNG\\s+HÀNG\\s+KHÔNG)?(?:\\s*/\\s*NAME)?|"
                    + "NGƯỜI\\s+KHAI\\s+THÁC|(?:CARRIER|OPERATOR)(?:\\s+NAME)?)\\s*:?$");
    /*
     * These patterns run against accent-folded text. Keep the classification
     * independent from the permit suffix: a document can be a schedule change
     * even when its permit number does not end in REV/RVS.
     */
    private static final Pattern REPLACEMENT_SCHEDULE_CONTEXT = Pattern.compile(
            "(?iu)(?:(?:LICH\\s+BAY|CHUYEN\\s+BAY|FLIGHT\\s+SCHEDULES?|SCHEDULES?)"
                    + ".{0,60}\\b(?:SUA\\s+DOI|THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG|MOI|"
                    + "NEW|REVISED|AMENDED|REPLACEMENT|CHANGES?|REVISION|AMENDMENT)\\b)"
                    + "|(?:\\b(?:SUA\\s+DOI|THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG|MOI|"
                    + "NEW|REVISED|AMENDED|REPLACEMENT)\\b"
                    + ".{0,60}(?:LICH\\s+BAY|CHUYEN\\s+BAY|FLIGHT\\s+SCHEDULES?|SCHEDULES?|FLIGHTS?))");
    private static final Pattern ORIGINAL_SCHEDULE_CONTEXT = Pattern.compile(
            "(?iu)(?:(?:LICH\\s+BAY|CHUYEN\\s+BAY|FLIGHT\\s+SCHEDULES?|SCHEDULES?)"
                    + ".{0,60}\\b(?:DU\\s+KIEN|CU|GOC|BAN\\s+DAU|OLD|ORIGINAL|PLANNED)\\b)"
                    + "|(?:\\b(?:DU\\s+KIEN|CU|GOC|BAN\\s+DAU|OLD|ORIGINAL|PLANNED)\\b"
                    + ".{0,60}(?:LICH\\s+BAY|CHUYEN\\s+BAY|FLIGHT\\s+SCHEDULES?|SCHEDULES?|FLIGHTS?))");
    private static final Map<String, List<String>> UNIVERSAL_COLUMN_ALIASES = Map.ofEntries(
            Map.entry("flightNumber", List.of("FlightNumber")),
            Map.entry("effectiveFrom", List.of("EffectiveFrom")),
            Map.entry("effectiveTo", List.of("EffectiveTo")),
            Map.entry("fromAirport", List.of(
                    "Dep airport", "Sân bay đi", "Sân bay điDeparture Airport")),
            Map.entry("etd", List.of(
                    "EOBT", "EOBT (UTC)", "Giờ dự kiến đi", "Giờ dự kiến điEOBT")),
            Map.entry("toAirport", List.of(
                    "Arr airport", "Sân bay đến", "Sân bay đếnArrival Airport")),
            Map.entry("eta", List.of(
                    "EIBT", "EIBT (UTC)", "Giờ dự kiến đến", "Giờ dựkiến đếnEIBT")),
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
    private final AircraftTypeCatalog aircraftTypeCatalog = new AircraftTypeCatalog();

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
                        carrierName == null || carrierName.isBlank()
                                ? null
                                : permitOperatorCatalog.operatorForIata(iataCode)),
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
        } catch (NonPermitDocumentException exception) {
            throw exception;
        } catch (FormatValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid(fileName, "Failed to parse Word permit: " + exception.getMessage());
        }
    }

    SchedulePermit parse(WordPermitDocument document, String fileName) {
        String rawContent = document.rawContent();
        if (Pattern.compile("(?iu)(?:PASSENGER\\s+LIST|DANH\\s+SÁCH\\s+HÀNH\\s+KHÁCH|"
                        + "APPLICATION\\s+(?:FORM|FOR\\s+REVISION|FOR\\s+NON[- ]SCHEDULED(?:\\s+FLIGHT)?|"
                        + "FOR\\s+NON\\s*SCHEDULED(?:\\s+FLIGHT)?|TO\\s+(?:REVISE|CANCEL))|"
                        + "ĐƠN\\s+(?:XIN|ĐỀ NGHỊ)|ĐỀ\\s+NGHỊ\\s+(?:SỬA\\s+ĐỔI|HỦY\\s+BỎ))")
                .matcher(rawContent).find()
                && !looksLikeIssuedPermitDocument(rawContent)) {
            throw new NonPermitDocumentException(
                    "Tài liệu đề nghị/danh sách hành khách, không phải phép bay đã cấp");
        }
        return parseProfile(formatDetector.detect(document, fileName), document, fileName);
    }

    /** Passenger lists are often appended to a valid issued permit. */
    static boolean looksLikeIssuedPermitDocument(String rawContent) {
        String value = rawContent == null ? "" : rawContent;
        boolean applicationHeading = Pattern.compile(
                "(?iu)\\b(?:APPLICATION\\s+(?:FOR\\s+REVISION|TO\\s+(?:REVISE|CANCEL))|"
                        + "APPLICATION\\s+FORM)\\b|"
                        + "(?:ĐƠN|DON)\\s+(?:XIN|ĐỀ\\s+NGHỊ|DE\\s+NGHI)|"
                        + "(?:ĐỀ\\s+NGHỊ|DE\\s+NGHI)\\s+(?:SỬA\\s+ĐỔI|SUA\\s+DOI|HỦY\\s+BỎ|HUY\\s+BO)")
                .matcher(value).find();
        boolean permitIdentity = Pattern.compile(
                "(?iu)\\b(?:LD|O\\s*/\\s*F|OF|QLB)\\s*[-:/ ]*\\d{1,5}")
                .matcher(value).find();
        boolean permitStructure = Pattern.compile(
                "(?iu)(?:LANDING\\s+PERMIT|PERMIT\\s+NO\\.?|"
                        + "SCHEDULES?|OLD\\s+SCHEDULES?|NEW\\s+SCHEDULES?|"
                        + "PHÉP\\s+BAY|PHEP\\s+BAY|LỊCH\\s+BAY)")
                .matcher(value).find();
        boolean issuedMarker = Pattern.compile(
                "(?iu)(?:PERMIT\\s+NO\\.?|LANDING\\s+PERMIT|"
                        + "CỤC\\s+HKVN\\s+CẤP\\s+PHÉP|CAAV\\s+(?:ISSUED|GRANTS?)|"
                        + "PHÉP\\s+BAY\\s+(?:ĐƯỢC|DUOC)\\s+CẤP)")
                .matcher(value).find();
        return permitIdentity && permitStructure && issuedMarker && !applicationHeading;
    }

    SchedulePermit parseNoReplacement(Path file) throws IOException {
        return parseNoReplacement(documentReader.read(file), file.getFileName().toString());
    }

    SchedulePermit parseNoReplacement(WordPermitDocument document, String fileName) {
        DocxPermitFormatProfile p = formatDetector.detect(document, fileName);
        var t = p.schedule();
        // Keep YAML aliases/date/time mappings; override NO replacement selection only.
        var schedule = new DocxPermitFormatProfile.ScheduleDefinition(t.columns(), t.headerlessColumns(),
                t.carryForwardColumns(), t.requiredColumns(), t.excludeColumns(), t.tableContextPatterns(),
                revisionMarkers(t.preferredTableContextPatterns(), "(?iu)(?:^|\\s)(?:NEW|REVISED|REVISE)\\b|LỊCH\\s+BAY\\s+MỚI"),
                revisionMarkers(t.originalTableContextPatterns(), "(?iu)(?:^|\\s)(?:OLD|ORIGINAL|ORIGIN)\\b|LỊCH\\s+BAY\\s+(?:GỐC|CŨ)"),
                t.supplementalTableContextPatterns(), t.dateFormats(), t.timeFormats(), t.locale(),
                t.purposeId(), true, t.lastMatchingTable(), t.inferIataPrefix());
        var r = p.route();
        var route = r == null ? null : new DocxPermitFormatProfile.RouteDefinition(r.columns(), r.requiredColumns(),
                Map.of(), r.tableRequired(), r.allowEmpty(), false, true, false);
        var adjusted = new DocxPermitFormatProfile(p.id(), p.priority(), p.detectionPatterns(), p.permit(),
                p.permitDate(), p.operator(), p.billingAddress(), p.reference(), p.referenceColumn(), p.purpose(),
                p.master(), schedule, route, p.aircraft(), p.validation());
        return parseProfile(adjusted, document, fileName, true);
    }

    private List<RouteRow> revisionRoutes(DocxPermitFormatProfile.RouteDefinition route,
                                          List<List<List<String>>> tables, boolean normalize, String fileName) {
        Map<String, RouteRow> latest = new LinkedHashMap<>();
        for (List<List<String>> table : tables) {
            for (RouteRow row : routeRows(route, List.of(table), normalize, fileName)) {
                latest.put(row.from() + "->" + row.to(), row);
            }
        }
        return List.copyOf(latest.values());
    }

    private List<String> revisionMarkers(List<String> configured, String common) {
        List<String> result = new ArrayList<>(safeList(configured)); result.add(common); return result;
    }

    private List<List<List<String>>> revisionValueTables(WordPermitDocument document, boolean old) {
        List<List<List<String>>> selected = new ArrayList<>();
        for (int i = 0; i < document.tables().size(); i++) {
            int role = RevisionDocumentText.role(document.tableContexts().get(i));
            if (old ? role <= 0 : role >= 0) selected.add(document.tables().get(i));
        }
        return selected;
    }

    private SchedulePermit parseProfile(DocxPermitFormatProfile profile,
                                        WordPermitDocument document,
                                        String fileName) {
        return parseProfile(profile, document, fileName, false);
    }

    private SchedulePermit parseProfile(DocxPermitFormatProfile profile,
                                        WordPermitDocument document,
                                        String fileName, boolean noReplacement) {
        Matcher permitMatcher = Pattern.compile(profile.permit().pattern())
                .matcher(joinSplitDigits(PermitIdentityText.normalize(document.rawContent())));
        if (!permitMatcher.find()) {
            String permitMismatch = permitIdentityMismatch(profile, document);
            if (hasScheduleTableEvidence(profile.schedule(), document)) {
                String scheduleMismatch = missingScheduleFields(profile, document);
                permitMismatch += " ??ng th?i: "
                        + scheduleMismatch.substring("WORD_FORMAT_MISMATCH: ".length());
            }
            throw invalid(fileName, permitMismatch);
        }
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
        String configuredBillingAddress = extractText(
                profile.billingAddress(), document, fileName, "billing address");

        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        boolean cancellationOnly = SchedulePermit.CANCEL_SEASONAL_PERMIT
                .equalsIgnoreCase(profile.master().workflow());
        List<List<List<String>>> scheduleTables = selectScheduleTables(schedule, document);
        List<List<List<String>>> originalScheduleTables = selectOriginalScheduleTables(schedule, document);
        if (scheduleTables.isEmpty()) {
            if (hasScheduleTableEvidence(schedule, document)) {
                throw invalid(fileName, missingScheduleFields(profile, document));
            }
            boolean hasInlineSections = !safeList(schedule.originalTableContextPatterns()).isEmpty()
                    && !safeList(schedule.preferredTableContextPatterns()).isEmpty();
            scheduleTables = inlineScheduleTables(hasInlineSections
                    ? inlineScheduleSection(document.paragraphText(), schedule, false)
                    : document.paragraphText());
            if (hasInlineSections && originalScheduleTables.isEmpty()) {
                originalScheduleTables = inlineScheduleTables(
                        inlineScheduleSection(document.paragraphText(), schedule, true));
            }
        }
        if ((scheduleTables.isEmpty() || scheduleTables.getFirst().size() < 2)
                && !cancellationOnly) {
            throw invalid(fileName, missingScheduleFields(profile, document));
        }
        String iataCode = extractIataPrefix(document.rawContent());
        String carrierName = extractCarrierName(document);
        // ATFM BILLINGADDRESS stores the Carrier/Operator Name from section 1.
        // Older profiles mapped a postal/billing section here; retain that only
        // as a fallback for documents which do not publish an operator Name.
        String billingAddress = carrierName == null || carrierName.isBlank()
                ? configuredBillingAddress
                : carrierName;
        String labeledIcaoCode = extractLabeledCode(document.rawContent(), ICAO_LABEL_PATTERN);
        String operatorId = labeledIcaoCode;
        String fixedOperator = normalizedOperator(
                profile.operator() == null ? null : profile.operator().fixedValue());
        if (fixedOperator != null) {
            operatorId = fixedOperator;
        }
        boolean hasIataLabel = IATA_LABEL_LINE_PATTERN.matcher(document.rawContent()).find();
        boolean hasIcaoLabel = ICAO_LABEL_LINE_PATTERN.matcher(document.rawContent()).find();
        boolean hasOperatorLabels = hasIataLabel || hasIcaoLabel;
        boolean iataIsNa = IATA_NA_PATTERN.matcher(document.rawContent()).find();
        boolean icaoIsNa = ICAO_NA_PATTERN.matcher(document.rawContent()).find();
        boolean revisionDocument = isRevisionDocument(
                permitNumber, normalizedPermitId, document.rawContent());
        if (!revisionDocument && fixedOperator == null && hasIataLabel && hasIcaoLabel) {
            if (iataIsNa && icaoIsNa) {
                // Explicit N/A for both codes denotes a private/non-scheduled operator.
                iataCode = null;
                operatorId = "PRV";
            } else if (iataCode == null && labeledIcaoCode == null) {
                operatorId = "PRV";
            }
        } else if (!revisionDocument && fixedOperator == null
                && hasOperatorLabels && iataCode == null && labeledIcaoCode == null) {
            operatorId = "PRV";
        }
        if (fixedOperator == null && operatorId == null && iataCode == null) {
            operatorId = normalizedOperator(
                    extractOptionalText(profile.operator(), document, fileName, "carrier ICAO code"));
        }
        if (operatorId == null && iataCode != null) {
            operatorId = permitOperatorResolver.resolve(iataCode, carrierName).orElse(null);
        }
        String scheduleCallsignPrefix = inferOperator(scheduleTables, schedule.columns());
        if (operatorId == null) {
            operatorId = permitOperatorResolver.resolveFromCallsign(
                    scheduleCallsignPrefix, carrierName).orElse(null);
        }
        if (operatorId == null) {
            operatorId = "PRV";
        }
        String resolvedOperatorId = operatorId;
        String iataPrefix = schedule.inferIataPrefix()
                ? iataCode != null
                        ? iataCode
                        : scheduleCallsignPrefix != null && scheduleCallsignPrefix.length() == 2
                                ? scheduleCallsignPrefix
                                : null
                : null;
        boolean normalizeAirportsToIcao = profile.validation() == null
                || !profile.validation().allowIataAirports();
        List<List<List<String>>> newValueTables = noReplacement ? revisionValueTables(document, false) : document.tables();
        List<List<List<String>>> scheduleAndOriginalTables = new ArrayList<>(scheduleTables);
        scheduleAndOriginalTables.addAll(originalScheduleTables);
        List<RouteRow> routes = noReplacement
                ? revisionRoutes(profile.route(), newValueTables, normalizeAirportsToIcao, fileName)
                : routeRows(profile.route(), newValueTables, normalizeAirportsToIcao, fileName);
        if (profile.route() != null && routes.isEmpty()) {
            routes = inferHeaderlessRoutes(newValueTables, scheduleAndOriginalTables, normalizeAirportsToIcao,
                    fileName);
        }
        List<RouteRow> oldRoutes = noReplacement ? revisionRoutes(profile.route(), revisionValueTables(document, true),
                normalizeAirportsToIcao, fileName) : routes;
        String purposeContent = noReplacement ? RevisionDocumentText.newValues(document.rawContent()) : document.rawContent();

        // Include every structural schedule, even OLD tables excluded by a profile
        // or legacy reader without heading contexts, before locating an aircraft table.
        List<List<List<String>>> allScheduleTables = findTableMatches(
                document.tables(), document.tableContexts(), schedule.columns(),
                schedule.requiredColumns(), List.of()).stream().map(TableMatch::table).toList();
        String parsedAuxiliaryAircraftTypes = auxiliaryAircraftTypes(
                profile.aircraft(), newValueTables, allScheduleTables);
        String auxiliaryRegistration = auxiliaryRegistration(profile.aircraft(), newValueTables);
        boolean aircraftTablePresent = parsedAuxiliaryAircraftTypes != null
                && !parsedAuxiliaryAircraftTypes.isBlank()
                || hasAircraftTableContent(newValueTables);
        // Never infer an aircraft from the complete document text: addresses,
        // telephone numbers and references can look like ICAO aircraft codes
        // (for example, THY's "76A Lelai" was previously stored as aircraft
        // type 76A). If a profile does not expose auxiliary aircraft columns,
        // read only the column headed "Aircraft Type" from the aircraft table.
        if (aircraftTablePresent && (parsedAuxiliaryAircraftTypes == null
                || parsedAuxiliaryAircraftTypes.isBlank())) {
            parsedAuxiliaryAircraftTypes = inferAircraftTypesFromAircraftTables(
                    newValueTables, allScheduleTables);
        }
        if ((parsedAuxiliaryAircraftTypes == null || parsedAuxiliaryAircraftTypes.isBlank())
                && !aircraftTablePresent) {
            HeaderlessAircraft inferred = inferHeaderlessAircraft(
                    newValueTables, allScheduleTables, profile.schedule().columns(), fileName);
            parsedAuxiliaryAircraftTypes = inferred == null ? null : inferred.aircraftType();
            if (auxiliaryRegistration == null && inferred != null) {
                auxiliaryRegistration = inferred.registration();
            }
            aircraftTablePresent = inferred != null;
        }
        String auxiliaryAircraftTypes = parsedAuxiliaryAircraftTypes;
        boolean hasAuxiliaryAircraftTable = aircraftTablePresent;
        String selectedAuxiliaryRegistration = auxiliaryRegistration;
        List<RouteRow> selectedRoutes = List.copyOf(routes);
        List<RouteRow> selectedOldRoutes = List.copyOf(oldRoutes);
        List<ScheduleFlight> flights = new ArrayList<>();
        for (List<List<String>> table : scheduleTables) {
            flights.addAll(scheduleFlights(
                    profile, table, documentTableIndex(document, table), selectedRoutes,
                    auxiliaryAircraftTypes, selectedAuxiliaryRegistration, hasAuxiliaryAircraftTable,
                    purposeContent, resolvedOperatorId, iataPrefix, normalizeAirportsToIcao,
                    false, fileName, noReplacement));
        }
        List<ScheduleFlight> originalFlights = new ArrayList<>();
        for (List<List<String>> table : originalScheduleTables) {
            originalFlights.addAll(scheduleFlights(
                    profile, table, documentTableIndex(document, table), selectedOldRoutes,
                    auxiliaryAircraftTypes, selectedAuxiliaryRegistration, hasAuxiliaryAircraftTable,
                    document.rawContent(), resolvedOperatorId, iataPrefix, normalizeAirportsToIcao,
                    true, fileName, noReplacement));
        }
        if (flights.isEmpty() && !cancellationOnly) {
            throw invalid(fileName, "No schedule rows found for profile " + profile.id());
        }

        String reference = extractText(profile.reference(), document, fileName, "permit reference");
        if ((reference == null || reference.isBlank())
                && profile.referenceColumn() != null
                && !profile.referenceColumn().isBlank()) {
            reference = joinedColumnValues(
                    document.tables(), profile.schedule().columns(), profile.referenceColumn());
        }
        String referencedPermitId = singleReferencedPermitId(
                originalScheduleTables, profile.schedule().columns(), profile.referenceColumn());

        DocxPermitFormatProfile.MasterDefaults master = profile.master();
        String sourcePermitNumber = profile.permit().sourceTemplate() == null
                || profile.permit().sourceTemplate().isBlank()
                ? permitMatcher.group().toUpperCase(Locale.ROOT)
                : expandTemplate(
                        profile.permit().sourceTemplate(), permitMatcher,
                        safeMap(profile.permit().zeroPadGroups()), fileName);
        normalizedPermitId = removeInventedSeasonMarker(
                sourcePermitNumber, normalizedPermitId);
        String version = permitVersion(permitMatcher, sourcePermitNumber);
        DocxPermitFormatProfile.ValidationRules validation = profile.validation();
        return new SchedulePermit(
                sourcePermitNumber,
                normalizedPermitId,
                permitNumber,
                master.authorId(),
                master.permitType(),
                version,
                permitSeason(permitMatcher, master.season()),
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
                originalFlights,
                referencedPermitId,
                false,
                master.workflow());
    }

    static String permitVersion(String sourcePermitNumber) {
        Matcher matcher = Pattern.compile("(?i)/(?<version>[A-Z])/(?:S|W)/")
                .matcher(sourcePermitNumber == null ? "" : sourcePermitNumber);
        return matcher.find()
                ? matcher.group("version").toUpperCase(Locale.ROOT)
                : "A";
    }

    private static String permitVersion(Matcher permitMatcher,
                                        String sourcePermitNumber) {
        try {
            String captured = permitMatcher.group("version");
            if (captured != null && !captured.isBlank()) {
                return captured.toUpperCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // This YAML does not define a version group; apply the documented fallback.
        }
        return permitVersion(sourcePermitNumber);
    }

    private static String permitSeason(Matcher permitMatcher, String fallback) {
        try {
            String captured = permitMatcher.group("season");
            if (captured != null && captured.matches("(?i)[SW]")) {
                return captured.toUpperCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // Non-seasonal profiles do not define a season group.
        }
        return fallback;
    }

    static String removeInventedSeasonMarker(String sourcePermitNumber,
                                               String normalizedPermitId) {
        if (normalizedPermitId == null
                || Pattern.compile("(?i)/(?:S|W)/")
                        .matcher(sourcePermitNumber == null ? "" : sourcePermitNumber).find()) {
            return normalizedPermitId;
        }
        return normalizedPermitId.replaceFirst("(?i)/(?:S|W)/", "/");
    }

    private String normalizedOperator(String value) {
        String normalized = value == null
                ? ""
                : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9]{3}") ? normalized : null;
    }

    /** Select paragraph sections using the same explicit YAML markers as table schedules. */
    private String inlineScheduleSection(String text,
                                         DocxPermitFormatProfile.ScheduleDefinition schedule,
                                         boolean original) {
        StringBuilder selected = new StringBuilder();
        Boolean activeOriginal = null;
        for (String line : text.split("\\R")) {
            if (matchesAny(line, schedule.originalTableContextPatterns())) {
                activeOriginal = true;
            } else if (matchesAny(line, schedule.preferredTableContextPatterns())) {
                activeOriginal = false;
            } else if (activeOriginal != null && activeOriginal == original) {
                selected.append(line).append('\n');
            }
        }
        return selected.toString();
    }

    /** Converts established English/Vietnamese paragraph schedules into a logical table. */
    private List<List<List<String>>> inlineScheduleTables(String paragraphText) {
        Pattern detailed = Pattern.compile(
                "(?imu)(?<![A-Z0-9])(?<flight>[A-Z0-9]+)\\s*[\\-–—]\\s*"
                        + "(?<fromDate>\\d{1,2}[A-Z]{3}\\d{2,4})\\s*[\\-–—]\\s*"
                        + "(?<toDate>\\d{1,2}[A-Z]{3}\\d{2,4})\\s*[\\-–—]\\s*"
                        + "(?<days>[0-7]{1,7}(?:\\s*[,/]\\s*[0-7])*)\\s*[\\-–—]\\s*"
                        + "(?<from>[A-Z]{3,4})\\s*[\\-–—]\\s*(?<etd>\\d{4})\\s*[\\-–—]\\s*"
                        + "(?<to>[A-Z]{3,4})\\s*[\\-–—]\\s*(?<eta>\\d{4}(?:\\+\\d)?)"
                        + "(?:\\s*[\\-–—]\\s*(?<aircraft>[A-Z0-9]{3,4}))?(?=\\s|$)");
        Pattern singleDate = Pattern.compile(
                "(?imu)(?<![A-Z0-9])(?<flight>[A-Z0-9]+)\\s*[\\-–—]\\s*"
                        + "(?<fromDate>\\d{1,2}[A-Z]{3}\\d{2,4})\\s*[\\-–—]\\s*"
                        + "(?<from>[A-Z]{3,4})\\s*[\\-–—]\\s*(?<etd>\\d{4})\\s*[\\-–—]\\s*"
                        + "(?<to>[A-Z]{3,4})\\s*[\\-–—]\\s*(?<eta>\\d{4}(?:\\+\\d)?)"
                        + "(?:\\s*[\\-–—]\\s*(?<aircraft>[A-Z0-9]{3,4}))?(?=\\s|$)");
        Pattern spaceSeparated = Pattern.compile(
                "(?imu)(?<![A-Z0-9])(?<flight>[A-Z0-9]+)\\s+"
                        + "(?<fromDate>\\d{1,2}[A-Z]{3}\\d{2,4})\\s+[0-7]{1,7}\\s+"
                        + "(?<from>[A-Z]{3,4})\\s+(?<etd>\\d{4})Z?\\s+"
                        + "(?<to>[A-Z]{3,4})\\s+(?<eta>\\d{4}(?:\\+\\d)?)(?:Z)?"
                        + "(?:\\s+(?<aircraft>[A-Z0-9]{3,4}))?(?=\\s|$)");
        List<List<String>> table = new ArrayList<>();
        table.add(List.of("Flight number", "Effective from", "Effective to",
                "Days of services", "Departure Airport", "ETD",
                "Arrival Airport", "ETA", "Aircraft Type"));
        addInlineScheduleRows(table, detailed.matcher(paragraphText), true);
        addInlineScheduleRows(table, singleDate.matcher(paragraphText), false);
        addInlineScheduleRows(table, spaceSeparated.matcher(paragraphText), false);
        return table.size() > 1 ? List.of(table) : List.of();
    }

    private void addInlineScheduleRows(List<List<String>> table,
                                       java.util.regex.Matcher matcher,
                                       boolean hasRange) {
        while (matcher.find()) {
            String fromDate = matcher.group("fromDate");
            String aircraft = matcher.group("aircraft");
            table.add(List.of(
                    matcher.group("flight"), fromDate,
                    hasRange ? matcher.group("toDate") : fromDate,
                    hasRange ? matcher.group("days").replaceAll("\\D", "") : "1",
                    matcher.group("from"), matcher.group("etd"), matcher.group("to"),
                    matcher.group("eta"), aircraft == null ? "" : aircraft));
        }
    }

    private String inferOperator(List<List<List<String>>> scheduleTables,
                                 Map<String, List<String>> aliases) {
        for (List<List<String>> scheduleTable : scheduleTables) {
            Map<String, Integer> columns = resolveColumns(scheduleTable.getFirst(), aliases);
            for (int rowIndex = 1; rowIndex < scheduleTable.size(); rowIndex++) {
                String sourceFlightNumber = clean(
                        value(scheduleTable.get(rowIndex), columns, "flightNumber"));
                if (sourceFlightNumber.toUpperCase(Locale.ROOT)
                        .matches("^[A-Z]{1,2}-[A-Z0-9]{3,5}$")) {
                    continue;
                }
                String flightNumber = sourceFlightNumber.replaceAll("[^A-Za-z0-9]", "")
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
        // Prefer the Carrier/Operator table identified by its IATA/ICAO labels.
        // This prevents a later section such as "2. Billing address" from
        // supplying its own Name value to ATFM BILLINGADDRESS.
        for (List<List<String>> table : document.tables()) {
            String tableText = table.stream()
                    .flatMap(List::stream)
                    .map(this::clean)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (IATA_LABEL_LINE_PATTERN.matcher(tableText).find()
                    || ICAO_LABEL_LINE_PATTERN.matcher(tableText).find()) {
                String carrierName = extractCarrierName(table);
                if (carrierName != null && !carrierName.isBlank()) {
                    return carrierName;
                }
            }
        }
        for (List<List<String>> table : document.tables()) {
            String carrierName = extractCarrierName(table);
            if (carrierName != null && !carrierName.isBlank()) {
                return carrierName;
            }
        }
        Matcher matcher = CARRIER_NAME_PATTERN.matcher("\n" + document.rawContent());
        return matcher.find() ? cleanCarrierName(matcher.group("value")) : null;
    }

    private String extractCarrierName(List<List<String>> table) {
        for (List<String> row : table) {
            for (int index = 0; index < row.size(); index++) {
                String cell = row.get(index);
                Matcher matcher = CARRIER_NAME_PATTERN.matcher("\n" + clean(cell));
                if (matcher.find()) {
                    return cleanCarrierName(matcher.group("value"));
                }
                if (CARRIER_NAME_LABEL_PATTERN.matcher(clean(cell)).matches()
                        && index + 1 < row.size()
                        && !clean(row.get(index + 1)).isBlank()) {
                    return cleanCarrierName(row.get(index + 1));
                }
            }
        }
        return null;
    }
    private String cleanCarrierName(String value) {
        return clean(value).replaceFirst(
                "(?iu)\\s+(?:ĐỊA\\s+CHỈ|DIA\\s+CHI|\\?{1,2}A\\s+CH\\?|"
                        + "POSTAL(?:\\s+ADDRESS)?|ADDRESS|IATA|ICAO)\\s*:.*$", "").trim();
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
                && operatorId != null
                && !"PRV".equals(operatorId)
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
                Objects.requireNonNullElse(summary, ""))) {
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
        return profile.schedule().purposeId() == null
                ? purpose.defaultId() : profile.schedule().purposeId();
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
                                                 int documentTableIndex,
                                                 List<RouteRow> routes,
                                                 String auxiliaryAircraftTypes,
                                                 String auxiliaryRegistration,
                                                 boolean aircraftTablePresent,
                                                 String rawContent,
                                                 String operatorId,
                                                 String iataPrefix,
                                                 boolean normalizeAirportsToIcao,
                                                 boolean allowMissingAircraft,
                                                 String fileName, boolean exactSchedule) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        Map<String, Integer> columns = resolveColumns(table.getFirst(), schedule.columns());
        int firstDataRow = 1;
        if (!columns.keySet().containsAll(safeList(schedule.requiredColumns()))
                && isHeaderlessScheduleTable(table, schedule)) {
            columns = new LinkedHashMap<>(safeMap(schedule.headerlessColumns()));
            firstDataRow = headerlessFirstDataRow(table, schedule);
        }
        List<ScheduleFlight> flights = new ArrayList<>();
        String previousServiceDays = null;
        Map<String, String> carriedValues = new LinkedHashMap<>();
        for (int rowIndex = firstDataRow; rowIndex < table.size(); rowIndex++) {
            List<String> row = normalizeScheduleRow(table.get(rowIndex), columns);
            if (isRepeatedScheduleHeader(row, columns)) {
                continue;
            }
            Map<String, String> rowValues = scheduleRowValues(
                    row, columns, schedule.carryForwardColumns(), carriedValues);
            String flightNumber = normalizeFlightNumber(
                    rowValues.getOrDefault("flightNumber", ""), operatorId, iataPrefix);
            if (flightNumber.isBlank()) {
                continue;
            }
            if (isScheduleSectionLabel(row, columns)) {
                continue;
            }
            String rawFrom = rowValues.getOrDefault("fromAirport", "").toUpperCase(Locale.ROOT);
            String rawTo = rowValues.getOrDefault("toAirport", "").toUpperCase(Locale.ROOT);
            String from = normalizeAirport(rawFrom, normalizeAirportsToIcao);
            String to = normalizeAirport(rawTo, normalizeAirportsToIcao);
            String[] routeCorrected = exactSchedule ? new String[] {from, to}
                    : correctTruncatedAirportFromPublishedRoute(from, to, routes);
            from = routeCorrected[0];
            to = routeCorrected[1];
            if (!exactSchedule && from.equals(to) && routes.size() == 1) {
                RouteRow publishedRoute = routes.getFirst();
                if (publishedRoute.from().equals(from) && !publishedRoute.to().equals(to)) {
                    to = publishedRoute.to();
                } else if (publishedRoute.to().equals(to)
                        && !publishedRoute.from().equals(from)) {
                    from = publishedRoute.from();
                }
            }
            AircraftSource aircraftSource = exactSchedule
                    ? aircraftType(profile.aircraft(), row, columns, null, false) : null;
            if (aircraftSource == null || aircraftSource.value() == null || aircraftSource.value().isBlank()) {
                aircraftSource = aircraftType(profile.aircraft(), row, columns, auxiliaryAircraftTypes, aircraftTablePresent);
            }
            String aircraftType = aircraftSource.value();
            String purposeId = purposeId(
                    profile, rawContent, rawFrom, rawTo, rowValues.getOrDefault("remark", ""));
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
            String[] effectiveDates = splitConcatenatedDateRange(
                    rowValues.getOrDefault("effectiveFrom", ""),
                    rowValues.getOrDefault("effectiveTo", ""));
            LocalDate beginDate;
            try {
                beginDate = parseDate(effectiveDates[0], schedule.dateFormats(), schedule.locale(),
                        fileName, "effective-from date", permitYear(rawContent));
            } catch (FormatValidationException exception) {
                throw invalid(fileName, formatValueMismatch(profile, documentTableIndex, rowIndex,
                        "effectiveFrom", effectiveDates[0], schedule.dateFormats()));
            }
            LocalDate endDate;
            try {
                endDate = parseDate(effectiveDates[1], schedule.dateFormats(), schedule.locale(),
                        fileName, "effective-to date", permitYear(rawContent));
            } catch (FormatValidationException exception) {
                throw invalid(fileName, formatValueMismatch(profile, documentTableIndex, rowIndex,
                        "effectiveTo", effectiveDates[1], schedule.dateFormats()));
            }
            if (!exactSchedule) beginDate = correctClearlyMistypedEffectiveYear(
                    beginDate, endDate, permitYear(rawContent));
            String rawServiceDays = rowValues.getOrDefault("serviceDays", "");
            String serviceDays = rawServiceDays.isBlank() && previousServiceDays != null
                    ? previousServiceDays
                    : normalizeDays(rawServiceDays, fileName);
            if (!exactSchedule && beginDate.equals(endDate)) {
                // A one-day effective window uniquely determines the operating weekday,
                // even when the source form's day flag contains a clerical mismatch.
                serviceDays = serviceDayFor(beginDate);
            }
            previousServiceDays = serviceDays;
            String rowRemark = remark(
                    profile.aircraft(),
                    purposeId,
                    aircraftSource.defaulted() ? null : aircraftType);
            if (allowMissingAircraft && profile.referenceColumn() != null) {
                String originalPermit = value(row, columns, profile.referenceColumn());
                if (!originalPermit.isBlank()) {
                    rowRemark = rowRemark == null || rowRemark.isBlank()
                            ? originalPermit
                            : rowRemark + " " + originalPermit;
                }
            }
            flights.add(new ScheduleFlight(
                    purposeId,
                    0L,
                    null,
                    flightNumber,
                    auxiliaryRegistration,
                    serviceDays,
                    from,
                    to,
                    normalizeScheduleTime(profile, documentTableIndex, rowIndex, "etd",
                            rowValues.getOrDefault("etd", ""), schedule.timeFormats(),
                            schedule.locale(), fileName, false),
                    schedule.includeEta() ? normalizeScheduleTime(profile, documentTableIndex,
                            rowIndex, "eta", rowValues.getOrDefault("eta", ""),
                            schedule.timeFormats(), schedule.locale(), fileName, true) : null,
                    via,
                    beginDate,
                    endDate,
                    rowRemark,
                    aircraftType,
                    aircraftTablePresent));
        }
        return flights;
    }

    private int documentTableIndex(WordPermitDocument document, List<List<String>> table) {
        for (int index = 0; index < document.tables().size(); index++) {
            if (document.tables().get(index) == table) {
                return index;
            }
        }
        return -1;
    }

    private String normalizeScheduleTime(DocxPermitFormatProfile profile, int tableIndex,
                                         int rowIndex, String field, String value,
                                         List<String> formats, String locale, String fileName,
                                         boolean nullable) {
        try {
            return normalizeTime(value, formats, locale, fileName,
                    field.equals("etd") ? "ETD" : "ETA", nullable, rowIndex);
        } catch (FormatValidationException exception) {
            throw invalid(fileName, formatValueMismatch(
                    profile, tableIndex, rowIndex, field, value, formats));
        }
    }

    private String formatValueMismatch(DocxPermitFormatProfile profile, int tableIndex,
                                       int rowIndex, String field, String value,
                                       List<String> expectedFormats) {
        String column = expectedAliases(profile.schedule(), List.of(field));
        String location = tableIndex < 0
                ? "d\u00f2ng " + (rowIndex + 1)
                : "B\u1ea3ng " + (tableIndex + 1) + ", d\u00f2ng " + (rowIndex + 1);
        return "WORD_FORMAT_MISMATCH: Format YAML: " + profile.id()
                + ". V\u1ecb tr\u00ed: " + location
                + ". C\u1ed9t/tr\u01b0\u1eddng: " + column
                + ". Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: '" + displayHeader(value) + "'. "
                + "Kh\u00e1c bi\u1ec7t: gi\u00e1 tr\u1ecb kh\u00f4ng kh\u1edbp ki\u1ec3u/ng\u00e0y gi\u1edd c\u1ee7a format. "
                + "Gi\u00e1 tr\u1ecb/format mong \u0111\u1ee3i: "
                + safeList(expectedFormats).stream().limit(8).collect(java.util.stream.Collectors.joining(" | "))
                + ". C\u00e1ch ch\u1ec9nh: s\u1eeda gi\u00e1 tr\u1ecb t\u1ea1i d\u00f2ng/c\u1ed9t n\u00eau tr\u00ean theo m\u1ed9t format \u0111\u01b0\u1ee3c li\u1ec7t k\u00ea.";
    }

    private Map<String, String> scheduleRowValues(
            List<String> row,
            Map<String, Integer> columns,
            List<String> carryForwardColumns,
            Map<String, String> carriedValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : columns.keySet()) {
            String current = value(row, columns, field);
            if (current.isBlank() && safeList(carryForwardColumns).contains(field)) {
                current = carriedValues.getOrDefault(field, "");
            }
            values.put(field, current);
            if (!current.isBlank() && safeList(carryForwardColumns).contains(field)) {
                carriedValues.put(field, current);
            }
        }
        return values;
    }

    static LocalDate correctClearlyMistypedEffectiveYear(LocalDate beginDate,
                                                         LocalDate endDate,
                                                         Integer permitYear) {
        if (permitYear == null || !beginDate.isBefore(endDate)
                || java.time.temporal.ChronoUnit.DAYS.between(beginDate, endDate) <= 366) {
            return beginDate;
        }
        LocalDate corrected;
        try {
            corrected = beginDate.withYear(permitYear);
        } catch (java.time.DateTimeException exception) {
            return beginDate;
        }
        long correctedDays = java.time.temporal.ChronoUnit.DAYS.between(corrected, endDate);
        return correctedDays >= 0 && correctedDays <= 366 ? corrected : beginDate;
    }

    private List<RouteRow> routeRows(DocxPermitFormatProfile.RouteDefinition route,
                                     List<List<List<String>>> tables,
                                     boolean normalizeAirportsToIcao,
                                     String fileName) {
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
        boolean hasRouteRow = false;
        String currentSector = null;
        Map<String, List<String>> airwaysBySector = new LinkedHashMap<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            String sector = clean(value(row, columns, "sector")).toUpperCase(Locale.ROOT);
            String rawAirways = value(row, columns, "airways");
            Matcher matcher = routeMatcher(sector);
            if (!matcher.matches()) {
                // Some CAAV airway tables append the corresponding flight number
                // to a valid sector, e.g. TPE-CGK(CI5869). Strip only that strict
                // parenthesized callsign suffix, then require a full sector match.
                String sectorWithoutFlight = sector.replaceFirst("\\s*\\([A-Z0-9]{2,10}\\)$", "");
                if (!sectorWithoutFlight.equals(sector)) {
                    matcher = routeMatcher(sectorWithoutFlight);
                }
            }
            if (matcher.matches()) {
                currentSector = matcher.group();
                hasRouteRow = true;
            } else if (!sector.isBlank()) {
                currentSector = null;
            }
            if (currentSector == null || rawAirways.isBlank()) {
                continue;
            }
            airwaysBySector.computeIfAbsent(currentSector, ignored -> new ArrayList<>())
                    .add(rawAirways);
        }
        for (Map.Entry<String, List<String>> entry : airwaysBySector.entrySet()) {
            Matcher matcher = routeMatcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            String airways = normalizeAirways(String.join(";", entry.getValue()));
            if (airways.isBlank()) {
                throw invalid(fileName, "Airways table is missing airways for sector " + entry.getKey());
            }
            for (String from : matcher.group(1).split("\\s*[/,]\\s*")) {
                for (String to : matcher.group(2).split("\\s*[/,]\\s*")) {
                    routes.add(new RouteRow(
                            normalizeAirport(from, normalizeAirportsToIcao),
                            normalizeAirport(to, normalizeAirportsToIcao),
                            airways));
                }
            }
        }
        if (!hasRouteRow && routes.isEmpty()) {
            throw invalid(fileName, "Airways table is present but contains no valid route rows");
        }
        return routes;
    }

    private Matcher routeMatcher(String value) {
        Matcher grouped = GROUPED_ROUTE_PATTERN.matcher(value);
        return grouped.matches() ? grouped : SLASH_ROUTE_PATTERN.matcher(value);
    }

    private List<RouteRow> inferHeaderlessRoutes(
            List<List<List<String>>> tables,
            List<List<List<String>>> scheduleTables,
            boolean normalizeAirportsToIcao,
            String fileName) {
        Set<List<List<String>>> schedules = new java.util.HashSet<>(scheduleTables);
        List<RouteRow> candidates = new ArrayList<>();
        boolean routeLikeTable = false;
        boolean ambiguousRouteRow = false;
        for (List<List<String>> table : safeList(tables)) {
            if (table == null || table.isEmpty() || schedules.contains(table)) {
                continue;
            }
            List<List<String>> rows = table;
            if (table.size() > 1 && rowLooksLikeRouteHeader(table.getFirst())) {
                continue;
            }
            for (List<String> row : rows) {
                LinkedHashSet<String> airportSet = new LinkedHashSet<>();
                for (String cell : row) {
                    String cleanCell = clean(cell).toUpperCase(Locale.ROOT).trim();
                    Matcher sectorMatcher = GROUPED_ROUTE_PATTERN.matcher(cleanCell);
                    if (sectorMatcher.matches()) {
                        Arrays.stream(sectorMatcher.group(1).split("\\s*/\\s*"))
                                .filter(this::looksLikeAirportCode)
                                .forEach(airportSet::add);
                        Arrays.stream(sectorMatcher.group(2).split("\\s*/\\s*"))
                                .filter(this::looksLikeAirportCode)
                                .forEach(airportSet::add);
                    } else if (looksLikeAirportCode(cleanCell)) {
                        airportSet.add(cleanCell);
                    }
                }
                List<String> airportCodes = List.copyOf(airportSet);
                List<String> airwayCandidates = row.stream()
                        .map(this::clean)
                        .filter(value -> !GROUPED_ROUTE_PATTERN.matcher(
                                value.toUpperCase(Locale.ROOT).trim()).matches())
                        .filter(this::looksLikeAirwaySequence)
                        .distinct().toList();
                if (airportCodes.size() >= 2 && !airwayCandidates.isEmpty()) {
                    routeLikeTable = true;
                    if (airportCodes.size() == 2 && airwayCandidates.size() == 1) {
                        String from = normalizeAirport(airportCodes.get(0), normalizeAirportsToIcao);
                        String to = normalizeAirport(airportCodes.get(1), normalizeAirportsToIcao);
                        String airway = normalizeAirways(airwayCandidates.getFirst());
                        if (!from.equals(to) && !airway.isBlank()) {
                            candidates.add(new RouteRow(from, to, airway));
                        } else {
                            ambiguousRouteRow = true;
                        }
                    } else {
                        ambiguousRouteRow = true;
                    }
                }
            }
        }
        List<RouteRow> uniqueRoutes = candidates.stream().distinct().toList();
        if (routeLikeTable && (uniqueRoutes.isEmpty() || ambiguousRouteRow)) {
            throw invalid(fileName,
                    "Headerless route table is ambiguous; airport/airway values could not be mapped uniquely");
        }
        return uniqueRoutes;
    }

    private boolean rowLooksLikeRouteHeader(List<String> row) {
        String normalized = row.stream().map(this::canonical).collect(java.util.stream.Collectors.joining(" "));
        return normalized.contains("sector") || normalized.contains("airway")
                || normalized.contains("route") || normalized.contains("from")
                || normalized.contains("to") || normalized.contains("airport");
    }

    private boolean tableLooksLikeSchedule(List<List<String>> table,
                                           Map<String, List<String>> scheduleAliases) {
        if (table == null || table.isEmpty()) {
            return false;
        }
        if (table.size() > 1 && headerLooksLikeSchedule(table.getFirst(), scheduleAliases)) {
            return true;
        }
        int scheduleRows = 0;
        for (List<String> row : table) {
            long dates = row.stream().filter(this::looksLikeScheduleDate).count();
            long airports = row.stream().filter(cell -> airportCodeCatalog.isKnown(clean(cell))).count();
            long times = row.stream().filter(cell -> clean(cell).matches("(?iu)\\d{3,4}(?:\\+1)?")).count();
            boolean flightNumber = row.stream().map(this::clean)
                    .anyMatch(cell -> cell.matches("(?iu)[A-Z0-9]{2,3}\\d{2,5}[A-Z]?"));
            if (flightNumber && dates >= 2 && airports >= 2 && times >= 1) {
                scheduleRows++;
            }
        }
        return scheduleRows > 0;
    }

    private boolean looksLikeScheduleDate(String value) {
        return Pattern.compile("(?iu)^(?:\\d{1,2}[A-Z]{3}\\d{2,4}|\\d{1,2}/\\d{1,2}/20\\d{2}|"
                        + "\\d{1,2}-[A-Z]{3}-\\d{2,4})$")
                .matcher(clean(value).replaceAll("\\s+", ""))
                .matches();
    }

    private boolean looksLikeAirwaySequence(String value) {
        String tokenized = clean(value).toUpperCase(Locale.ROOT).trim();
        if (tokenized.length() < 2 || tokenized.length() > 120) {
            return false;
        }
        String[] tokens = tokenized.split("\\s*[-/;\\n]\\s*");
        if (tokens.length == 0 || tokens.length > 20) {
            return false;
        }
        return Arrays.stream(tokens).allMatch(token ->
                token.matches("[A-Z]{1,2}\\d{1,4}[A-Z]?") || token.matches("DCT"));
    }

    private HeaderlessAircraft inferHeaderlessAircraft(
            List<List<List<String>>> tables,
            List<List<List<String>>> scheduleTables,
            Map<String, List<String>> scheduleAliases,
            String fileName) {
        Set<List<List<String>>> schedules = new java.util.HashSet<>(scheduleTables);
        LinkedHashSet<String> aircraftTypes = new LinkedHashSet<>();
        LinkedHashSet<String> registrations = new LinkedHashSet<>();
        boolean aircraftLikeTable = false;
        boolean ambiguousAircraftRow = false;
        for (List<List<String>> table : safeList(tables)) {
            if (table == null || table.isEmpty() || schedules.contains(table)
                    || tableLooksLikeSchedule(table, Map.of())) {
                continue;
            }
            List<String> header = table.getFirst();
            if (table.size() > 1 && tableLooksLikeSchedule(table, scheduleAliases)) {
                continue;
            }
            List<List<String>> dataRows = table.size() > 1 ? table.subList(1, table.size()) : table;
            for (List<String> row : dataRows) {
                if (looksLikeScheduleDataRow(row)) {
                    continue;
                }
                List<List<String>> typeCells = row.stream()
                        .map(cell -> aircraftTypeCatalog.knownCandidates(clean(cell)))
                        .filter(candidates -> !candidates.isEmpty())
                        .toList();
                List<String> knownAircraft = typeCells.stream().flatMap(List::stream).distinct().toList();
                List<String> registrationCodes = row.stream()
                        .map(this::clean)
                        .map(String::trim)
                        .filter(this::looksLikeRegistration)
                        .distinct()
                        .toList();
                if (!knownAircraft.isEmpty()) {
                    aircraftLikeTable = true;
                    if (typeCells.size() > 1 || typeCells.stream().anyMatch(candidates -> candidates.size() > 1)) {
                        ambiguousAircraftRow = true;
                    }
                    if (knownAircraft.size() == 1 && typeCells.stream().allMatch(candidates -> candidates.size() == 1)) {
                        aircraftTypes.add(knownAircraft.getFirst());
                    } else {
                        aircraftTypes.addAll(knownAircraft);
                    }
                    registrations.addAll(registrationCodes);
                }
            }
        }
        if (!aircraftLikeTable) {
            return null;
        }
        if (ambiguousAircraftRow || aircraftTypes.size() != 1 || registrations.size() > 1) {
            throw invalid(fileName,
                    "Headerless aircraft table is ambiguous; aircraft type/registration could not be mapped uniquely");
        }
        return new HeaderlessAircraft(aircraftTypes.getFirst(),
                registrations.isEmpty() ? null : registrations.getFirst());
    }

    private boolean headerLooksLikeSchedule(List<String> header,
                                            Map<String, List<String>> aliases) {
        Map<String, Integer> mapped = resolveColumns(header, aliases);
        return mapped.containsKey("flightNumber")
                || mapped.containsKey("effectiveFrom")
                || mapped.containsKey("fromAirport")
                || mapped.containsKey("toAirport");
    }

    private boolean looksLikeRegistration(String value) {
        String compact = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return compact.matches("[A-Z0-9]{2,3}-?[A-Z0-9]{3,5}")
                && !airportCodeCatalog.isKnown(compact)
                && aircraftTypeCatalog.knownCandidates(compact).isEmpty();
    }

    private boolean looksLikeAirportCode(String value) {
        String code = airportCodeCatalog.canonicalize(value);
        return airportCodeCatalog.isKnown(code) || code.matches("[A-Z]{4}");
    }

    private boolean looksLikeScheduleDataRow(List<String> row) {
        long dates = row.stream().filter(this::looksLikeScheduleDate).count();
        long airports = row.stream().filter(cell -> looksLikeAirportCode(clean(cell))).count();
        long times = row.stream().filter(cell -> clean(cell).matches("(?iu)\\d{3,4}(?:\\+1)?")).count();
        boolean flightNumber = row.stream().map(this::clean)
                .anyMatch(cell -> cell.matches("(?iu)[A-Z0-9]{2,3}\\d{2,5}[A-Z]?"));
        return flightNumber && dates >= 2 && airports >= 2 && times >= 1;
    }

    private List<String> normalizeScheduleRow(List<String> row, Map<String, Integer> columns) {
        if (row.size() >= 8
                && clean(row.get(1)).matches("[1-7]")
                && looksLikeScheduleDate(clean(row.get(2)))
                && looksLikeScheduleDate(clean(row.get(3)))
                && looksLikeAirportCode(clean(row.get(4)))
                && looksLikeAirportCode(clean(row.get(5)))
                && clean(row.get(6)).matches("\\d{1,2}:\\d{2}")) {
            return List.of(row.get(0), row.get(2), row.get(3), row.get(1),
                    row.get(4), row.get(6), row.get(5), row.get(7));
        }
        Integer serviceIndex = columns.get("serviceDays");
        Integer effectiveIndex = columns.get("effectiveFrom");
        if (serviceIndex == null || effectiveIndex == null
                || effectiveIndex >= row.size()) {
            return row;
        }
        String service = clean(row.get(serviceIndex));
        String effective = clean(row.get(effectiveIndex));
        if (serviceIndex != effectiveIndex + 1
                && service.matches("[1-7]")
                && looksLikeScheduleDate(effective)) {
            List<String> normalized = new ArrayList<>(row);
            normalized.remove(serviceIndex);
            return normalized;
        }
        if (effective.matches("[1-7]") && effectiveIndex + 1 < row.size()
                && looksLikeScheduleDate(clean(row.get(effectiveIndex + 1)))) {
            List<String> normalized = new ArrayList<>(row);
            normalized.remove(effectiveIndex);
            return normalized;
        }
        if (service.matches("[1-7]") && looksLikeScheduleDate(effective)) {
            List<String> normalized = new ArrayList<>(row);
            normalized.remove(serviceIndex);
            return normalized;
        }
        return row;
    }
    private boolean isRevisionDocument(String permitNumber,
                                       String normalizedPermitId,
                                       String rawContent) {
        String folded = Normalizer.normalize(String.join("\n",
                        Objects.requireNonNullElse(permitNumber, ""),
                        Objects.requireNonNullElse(normalizedPermitId, ""),
                        Objects.requireNonNullElse(rawContent, ""))
                        .replace('Đ', 'D').replace('đ', 'd'),
                Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return Pattern.compile("(?iu)\\b(?:REV(?:ISION)?|RVS)\\s*\\d*\\b")
                .matcher(folded).find()
                || REPLACEMENT_SCHEDULE_CONTEXT.matcher(folded).find();
    }

    private String normalizeAirport(String value, boolean normalizeToIcao) {
        return normalizeToIcao
                ? airportCodeCatalog.normalize(value)
                : airportCodeCatalog.canonicalize(value);
    }

    private String auxiliaryAircraftTypes(DocxPermitFormatProfile.AircraftDefinition aircraft,
                                           List<List<List<String>>> tables,
                                           List<List<List<String>>> scheduleTables) {
        if (aircraft == null || safeMap(aircraft.auxiliaryColumns()).isEmpty()
                || aircraft.auxiliaryTypeColumn() == null
                || aircraft.auxiliaryTypeColumn().isBlank()) {
            return null;
        }
        List<List<String>> table = findTable(
                tables.stream().filter(candidate -> !scheduleTables.contains(candidate)).toList(),
                List.of(),
                aircraft.auxiliaryColumns(),
                List.of(aircraft.auxiliaryTypeColumn()),
                List.of(),
                List.of(),
                aircraft.lastMatchingTable());
        if (table == null || table.size() < 2) {
            return null;
        }
        if (scheduleTables.stream().anyMatch(table::equals)) {
            return null;
        }
        Map<String, Integer> columns = resolveColumns(table.getFirst(), aircraft.auxiliaryColumns());
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            String type = AircraftTypeNotation.normalize(
                    value(table.get(rowIndex), columns, aircraft.auxiliaryTypeColumn()));
            if (type != null && !type.isBlank()) {
                for (String token : type.split("/")) {
                    if (!token.equalsIgnoreCase("SUB") && !token.equalsIgnoreCase("SUBS")) {
                        types.add(token);
                    }
                }
            }
        }
        return types.isEmpty() ? null : String.join("/", types);
    }

    private boolean auxiliaryAircraftTablePresent(
            DocxPermitFormatProfile.AircraftDefinition aircraft,
            List<List<List<String>>> tables) {
        if (aircraft == null || safeMap(aircraft.auxiliaryColumns()).isEmpty()
                || aircraft.auxiliaryTypeColumn() == null
                || aircraft.auxiliaryTypeColumn().isBlank()) {
            return false;
        }
        return findTable(
                tables, List.of(), aircraft.auxiliaryColumns(),
                aircraft.auxiliaryRequiredColumns(), List.of(), List.of(),
                aircraft.lastMatchingTable()) != null;
    }

    private String[] correctTruncatedAirportFromPublishedRoute(String from,
                                                                String to,
                                                                List<RouteRow> routes) {
        if (from.length() == 3) {
            String candidateFrom = from;
            String candidateTo = to;
            List<String> candidates = routes.stream()
                    .filter(route -> route.to().equals(candidateTo)
                            && route.from().endsWith(candidateFrom))
                    .map(RouteRow::from).distinct().toList();
            if (candidates.size() == 1) {
                from = candidates.getFirst();
            }
        }
        if (to.length() == 3) {
            String candidateFrom = from;
            String candidateTo = to;
            List<String> candidates = routes.stream()
                    .filter(route -> route.from().equals(candidateFrom)
                            && route.to().endsWith(candidateTo))
                    .map(RouteRow::to).distinct().toList();
            if (candidates.size() == 1) {
                to = candidates.getFirst();
            }
        }
        return new String[] {from, to};
    }

    private Integer permitYear(String content) {
        Matcher matcher = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)").matcher(content);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private String auxiliaryRegistration(DocxPermitFormatProfile.AircraftDefinition aircraft,
                                         List<List<List<String>>> tables) {
        if (aircraft == null || aircraft.registrationColumn() == null
                || aircraft.registrationColumn().isBlank()
                || safeMap(aircraft.auxiliaryColumns()).isEmpty()) {
            return null;
        }
        List<List<String>> table = findTable(
                tables, List.of(), aircraft.auxiliaryColumns(),
                aircraft.auxiliaryRequiredColumns(), List.of(), List.of(),
                aircraft.lastMatchingTable());
        if (table == null || table.size() < 2) {
            return null;
        }
        Map<String, Integer> columns = resolveColumns(table.getFirst(), aircraft.auxiliaryColumns());
        Integer registrationIndex = columns.get(aircraft.registrationColumn());
        if (registrationIndex == null) {
            return null;
        }
        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            String registration = clean(value(table.get(rowIndex), columns, aircraft.registrationColumn()));
            if (!registration.isBlank()) {
                return registration;
            }
        }
        return null;
    }

    private AircraftSource aircraftType(DocxPermitFormatProfile.AircraftDefinition aircraft,
                                        List<String> row,
                                        Map<String, Integer> columns,
                                        String auxiliaryTypes,
                                        boolean aircraftTablePresent) {
        if (aircraftTablePresent) {
            return new AircraftSource(auxiliaryTypes, false);
        }
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
        return new AircraftSource(null, false);
    }

    private String inferAircraftTypes(String rawContent) {
        Matcher matcher = Pattern.compile(
                "(?iu)(?<![A-Z0-9])(?:A\\d{3}|B\\d{3}|\\d{2}[A-Z]|\\d{3})(?![A-Z0-9])")
                .matcher(Objects.requireNonNullElse(rawContent, ""));
        LinkedHashSet<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return values.isEmpty() ? null : String.join("/", values);
    }

    private String inferAircraftTypesFromAircraftTables(
            List<List<List<String>>> tables,
            List<List<List<String>>> scheduleTables) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (List<List<String>> table : safeList(tables)) {
            if (table == null || table.size() < 2 || scheduleTables.stream().anyMatch(table::equals)) {
                continue;
            }
            List<String> header = table.getFirst();
            Map<String, Integer> columns = resolveColumns(
                    header, Map.of("aircraftType", List.of("Aircraft Type", "Loại tàu bay")));
            Integer typeColumn = columns.get("aircraftType");
            if (typeColumn == null) {
                continue;
            }
            for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
                String cell = typeColumn < table.get(rowIndex).size()
                        ? clean(table.get(rowIndex).get(typeColumn))
                        : "";
                if (!cell.isBlank()) {
                    values.add(cell.toUpperCase(Locale.ROOT));
                }
            }
        }
        return values.isEmpty() ? null : String.join("/", values);
    }

    private boolean hasAircraftTableContent(List<List<List<String>>> tables) {
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) {
                continue;
            }
            List<String> header = table.getFirst().stream()
                    .map(value -> clean(value).toUpperCase(Locale.ROOT))
                    .toList();
            boolean hasAircraftHeader = header.stream().anyMatch(value ->
                    value.contains("AIRCRAFT") || value.contains("TÀU BAY") || value.equals("TAU"));
            boolean looksLikeSchedule = header.stream().anyMatch(value ->
                    value.contains("FLIGHT NUMBER") || value.contains("FLT NUMBER")
                            || value.contains("EFFECTIVE FROM") || value.contains("DEPARTURE AIRPORT")
                            || value.contains("SỐ HIỆU") || value.contains("HIỆU LỰC")
                            || value.contains("SÂN BAY") || value.contains("NGÀY KHAI THÁC")
                            || value.contains("NGAY KHAI THAC"));
            if (!hasAircraftHeader || looksLikeSchedule) {
                continue;
            }
            for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
                String row = String.join(" ", table.get(rowIndex));
                if (inferAircraftTypes(row) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private String remark(DocxPermitFormatProfile.AircraftDefinition aircraft,
                          String purposeId,
                          String aircraftType) {
        String type = aircraftType == null ? "" : aircraftType.trim();
        return type.isBlank() ? null : type;
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
            ScheduleSection explicitSection = scheduleSection(match, schedule);
            if (explicitSection != ScheduleSection.UNKNOWN) {
                activeSection = explicitSection;
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
            ScheduleSection explicitSection = scheduleSection(match, schedule);
            if (explicitSection != ScheduleSection.UNKNOWN) {
                activeSection = explicitSection;
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

    private ScheduleSection scheduleSection(
            TableMatch match,
            DocxPermitFormatProfile.ScheduleDefinition schedule) {
        // Prefer the actual heading. Appending column headers can make
        // "Lich bay moi ... Gio du kien di" match ORIGINAL through "du kien".
        ScheduleSection heading = scheduleSection(match.context(), schedule);
        return heading != ScheduleSection.UNKNOWN
                ? heading : scheduleSection(scheduleSectionText(match), schedule);
    }

    private ScheduleSection scheduleSection(
            String context,
            DocxPermitFormatProfile.ScheduleDefinition schedule) {
        // A profile-specific replacement marker must win if a broad context
        // happens to mention both the old and replacement schedule headings.
        boolean preferred = matchesAny(context, schedule.preferredTableContextPatterns());
        boolean original = matchesAny(context, schedule.originalTableContextPatterns());
        if (preferred && !original) {
            return ScheduleSection.REPLACEMENT;
        }
        if (original && !preferred) {
            return ScheduleSection.ORIGINAL;
        }
        String folded = foldScheduleSectionText(context);
        int replacementMarker = lastMatchStart(REPLACEMENT_SCHEDULE_CONTEXT, folded);
        int originalMarker = lastMatchStart(ORIGINAL_SCHEDULE_CONTEXT, folded);
        if (replacementMarker >= 0 || originalMarker >= 0) {
            // Context may contain a broad section title such as "Revised schedule"
            // followed by the local subsection "2.1 Original schedule". The last
            // marker is the heading nearest to this table and therefore wins.
            return originalMarker > replacementMarker
                    ? ScheduleSection.ORIGINAL
                    : ScheduleSection.REPLACEMENT;
        }
        return ScheduleSection.UNKNOWN;
    }

    private int lastMatchStart(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        int last = -1;
        while (matcher.find()) {
            last = matcher.start();
        }
        return last;
    }

    private String foldScheduleSectionText(String value) {
        return Normalizer.normalize(
                        value == null ? "" : value.replace('Đ', 'D').replace('đ', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
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
        return headerlessFirstDataRow(table, schedule) >= 0;
    }

    private int headerlessFirstDataRow(
            List<List<String>> table,
            DocxPermitFormatProfile.ScheduleDefinition schedule) {
        Map<String, Integer> indexes = safeMap(schedule.headerlessColumns());
        for (int rowIndex = 0; rowIndex < Math.min(2, table.size()); rowIndex++) {
            List<String> first = table.get(rowIndex);
            String flight = value(first, indexes, "flightNumber").replaceAll("[^A-Za-z0-9]", "");
            String effectiveFrom = value(first, indexes, "effectiveFrom");
            String from = value(first, indexes, "fromAirport").replaceAll("[^A-Za-z]", "");
            String to = value(first, indexes, "toAirport").replaceAll("[^A-Za-z]", "");
            if (flight.matches("(?i)[A-Z0-9]{3,10}")
                    && effectiveFrom.matches("(?i).*[0-9].*")
                    && from.matches("(?i)[A-Z]{3,4}")
                    && to.matches("(?i)[A-Z]{3,4}")
                    && !isHeaderlessHeaderRow(first, indexes)) {
                return rowIndex;
            }
        }
        return -1;
    }

    private boolean isHeaderlessHeaderRow(List<String> row, Map<String, Integer> indexes) {
        String flight = canonicalHeader(value(row, indexes, "flightNumber"));
        String effectiveFrom = canonicalHeader(value(row, indexes, "effectiveFrom"));
        return flight.equals("flight") || flight.equals("flightnumber")
                || effectiveFrom.equals("effective")
                || effectiveFrom.equals("effectivefrom");
    }

    private boolean hasScheduleTableEvidence(DocxPermitFormatProfile.ScheduleDefinition schedule,
                                            WordPermitDocument document) {
        for (List<List<String>> table : document.tables()) {
            if (table.isEmpty()) continue;
            if (resolveColumns(table.getFirst(), schedule.columns()).size() >= 2
                    || tableLooksLikeSchedule(table, schedule.columns())) {
                return true;
            }
        }
        return false;
    }

    private String permitIdentityMismatch(DocxPermitFormatProfile profile,
                                          WordPermitDocument document) {
        List<String> lines = document.rawContent().lines().toList();
        Pattern permitLabel = Pattern.compile("(?iu)\\b(?:LD|O\\s*/\\s*F|OF)\\b[^\\r\\n]{0,80}");
        for (int index = 0; index < lines.size(); index++) {
            Matcher actual = permitLabel.matcher(lines.get(index));
            if (!actual.find()) continue;
            String expected = profile.permit().sourceTemplate();
            if (expected == null || expected.isBlank()) expected = profile.permit().pattern();
            return "WORD_FORMAT_MISMATCH: Format YAML: " + profile.id()
                    + ". V\u1ecb tr\u00ed: d\u00f2ng " + (index + 1)
                    + ". Tr\u01b0\u1eddng: s\u1ed1 gi\u1ea5y ph\u00e9p. Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: '"
                    + displayHeader(actual.group().trim()) + "'. Kh\u00e1c bi\u1ec7t: m\u00e3 kh\u00f4ng kh\u1edbp pattern permit c\u1ee7a profile. "
                    + "Gi\u00e1 tr\u1ecb/format mong \u0111\u1ee3i: " + expected
                    + ". C\u00e1ch ch\u1ec9nh: d\u00f9ng m\u1ed9t d\u1ea5u ph\u00e2n c\u00e1ch sau LD, gi\u1eef c\u00e1c ph\u1ea7n version/m\u00f9a/n\u0103m theo m\u1eabu.";
        }
        return "WORD_FORMAT_MISMATCH: Format YAML: " + profile.id()
                + ". V\u1ecb tr\u00ed: to\u00e0n v\u0103n b\u1ea3n permit. Tr\u01b0\u1eddng: s\u1ed1 gi\u1ea5y ph\u00e9p. "
                + "Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: kh\u00f4ng t\u00ecm th\u1ea5y. Gi\u00e1 tr\u1ecb/format mong \u0111\u1ee3i: "
                + profile.permit().sourceTemplate() + ". C\u00e1ch ch\u1ec9nh: b\u1ed5 sung s\u1ed1 permit theo m\u1eabu c\u1ee7a format YAML.";
    }

    private String missingScheduleFields(DocxPermitFormatProfile profile,
                                         WordPermitDocument document) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        ScheduleTableDifference closest = null;
        for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
            List<List<String>> table = document.tables().get(tableIndex);
            if (table.isEmpty()) continue;
            Map<String, Integer> columns = resolveColumns(table.getFirst(), schedule.columns());
            List<String> missing = safeList(schedule.requiredColumns()).stream()
                    .filter(required -> !columns.containsKey(required)).toList();
            ScheduleTableDifference candidate = new ScheduleTableDifference(
                    tableIndex, table.getFirst(), columns, missing);
            if (closest == null || candidate.missingFields().size() < closest.missingFields().size()
                    || (candidate.missingFields().size() == closest.missingFields().size()
                    && candidate.matchedColumns().size() > closest.matchedColumns().size())) closest = candidate;
        }
        if (closest == null) {
            return "WORD_FORMAT_MISMATCH: Format YAML: " + profile.id()
                    + ". V\u1ecb tr\u00ed: to\u00e0n b\u1ed9 t\u00e0i li\u1ec7u Word. Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: kh\u00f4ng c\u00f3 b\u1ea3ng n\u00e0o. "
                    + "Kh\u00e1c bi\u1ec7t: thi\u1ebfu b\u1ea3ng l\u1ecbch bay. Gi\u00e1 tr\u1ecb/alias mong \u0111\u1ee3i: "
                    + expectedAliases(schedule, schedule.requiredColumns())
                    + ". C\u00e1ch ch\u1ec9nh: th\u00eam b\u1ea3ng l\u1ecbch bay theo \u0111\u00fang c\u00e1c c\u1ed9t b\u1eaft bu\u1ed9c c\u1ee7a format YAML n\u00e0y.";
        }
        ScheduleTableDifference difference = closest;
        String location = "B\u1ea3ng " + (difference.tableIndex() + 1) + ", d\u00f2ng ti\u00eau \u0111\u1ec1 1";
        String actualHeaders = indexedHeaders(difference.header());
        if (difference.missingFields().isEmpty()) {
            return "WORD_FORMAT_MISMATCH: Format YAML: " + profile.id()
                    + ". V\u1ecb tr\u00ed: " + location + ". Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: " + actualHeaders
                    + ". Kh\u00e1c bi\u1ec7t: c\u00e1c c\u1ed9t \u0111\u00e3 kh\u1edbp nh\u01b0ng b\u1ea3ng n\u1eb1m sai ph\u1ea7n/ti\u00eau \u0111\u1ec1 ng\u1eef c\u1ea3nh c\u1ee7a m\u1eabu. "
                    + "Gi\u00e1 tr\u1ecb/alias mong \u0111\u1ee3i: ti\u00eau \u0111\u1ec1 ph\u1ea7n l\u1ecbch bay theo format YAML. "
                    + "C\u00e1ch ch\u1ec9nh: \u0111\u1eb7t b\u1ea3ng d\u01b0\u1edbi \u0111\u00fang ti\u00eau \u0111\u1ec1 ph\u1ea7n l\u1ecbch bay m\u1edbi/\u0111\u01b0\u1ee3c s\u1eeda \u0111\u1ed5i c\u1ee7a m\u1eabu; "
                    + "n\u1ebfu t\u00e0i li\u1ec7u d\u00f9ng b\u1ed1 c\u1ee5c m\u1edbi, g\u1eedi file m\u1eabu cho qu\u1ea3n tr\u1ecb \u0111\u1ec3 c\u1eadp nh\u1eadt YAML.";
        }
        Set<Integer> recognizedIndexes = new LinkedHashSet<>(difference.matchedColumns().values());
        String unrecognized = java.util.stream.IntStream.range(0, difference.header().size())
                .filter(index -> !recognizedIndexes.contains(index))
                .mapToObj(index -> "C" + (index + 1) + "='" + displayHeader(difference.header().get(index)) + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return "WORD_FORMAT_MISMATCH: Format YAML: " + profile.id()
                + ". V\u1ecb tr\u00ed: " + location + ". Gi\u00e1 tr\u1ecb hi\u1ec7n c\u00f3: " + actualHeaders
                + (unrecognized.isBlank() ? "" : ". C\u1ed9t ch\u01b0a nh\u1eadn di\u1ec7n: " + unrecognized)
                + ". Kh\u00e1c bi\u1ec7t: thi\u1ebfu/kh\u00f4ng kh\u1edbp tr\u01b0\u1eddng b\u1eaft bu\u1ed9c "
                + String.join(", ", difference.missingFields())
                + ". Gi\u00e1 tr\u1ecb/alias mong \u0111\u1ee3i: " + expectedAliases(schedule, difference.missingFields())
                + ". C\u00e1ch ch\u1ec9nh: \u0111\u1ed5i t\u00ean ho\u1eb7c b\u1ed5 sung c\u1ed9t t\u1ea1i d\u00f2ng ti\u00eau \u0111\u1ec1 b\u1eb1ng m\u1ed9t alias \u0111\u01b0\u1ee3c li\u1ec7t k\u00ea; "
                + "kh\u00f4ng thay \u0111\u1ed5i d\u1eef li\u1ec7u c\u00e1c d\u00f2ng n\u1ebfu ch\u1ec9 sai t\u00ean c\u1ed9t.";
    }

    private String expectedAliases(DocxPermitFormatProfile.ScheduleDefinition schedule, List<String> fields) {
        return fields.stream().map(field -> {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            aliases.addAll(safeList(schedule.columns().get(field)));
            aliases.addAll(safeList(UNIVERSAL_COLUMN_ALIASES.get(field)));
            return field + " = [" + aliases.stream().limit(8)
                    .collect(java.util.stream.Collectors.joining(" | ")) + "]";
        }).collect(java.util.stream.Collectors.joining("; "));
    }

    private String indexedHeaders(List<String> header) {
        if (header.isEmpty()) return "d\u00f2ng ti\u00eau \u0111\u1ec1 tr\u1ed1ng";
        return java.util.stream.IntStream.range(0, header.size())
                .mapToObj(index -> "C" + (index + 1) + "='" + displayHeader(header.get(index)) + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String displayHeader(String value) {
        String header = clean(value);
        return header.isBlank() ? "<tr\u1ed1ng>" : header.replace("'", "\u2019");
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

    private String singleReferencedPermitId(List<List<List<String>>> tables,
                                            Map<String, List<String>> aliases,
                                            String semanticColumn) {
        if (semanticColumn == null || semanticColumn.isBlank()) {
            return null;
        }
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        for (List<List<String>> table : tables) {
            if (table.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = resolveColumns(table.getFirst(), aliases);
            if (!columns.containsKey(semanticColumn)) {
                continue;
            }
            for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
                AtfmPermitIdNormalizer.normalizeReference(
                                value(table.get(rowIndex), columns, semanticColumn))
                        .ifPresent(normalizedIds::add);
            }
        }
        return normalizedIds.size() == 1 ? normalizedIds.getFirst() : null;
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
            case "TABLE_WITH_CONTEXT" -> tableTextWithContext(document);
            case "RAW" -> document.rawContent();
            default -> throw new IllegalArgumentException("Unknown text source: " + source);
        };
    }

    private String tableTextWithContext(WordPermitDocument document) {
        StringBuilder text = new StringBuilder();
        for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
            if (tableIndex < document.tableContexts().size()) {
                text.append(document.tableContexts().get(tableIndex)).append('\n');
            }
            for (List<String> row : document.tables().get(tableIndex)) {
                text.append(String.join(" | ", row)).append('\n');
            }
        }
        return text.toString();
    }

    private String expandTemplate(String template,
                                  Matcher matcher,
                                  Map<String, Integer> zeroPadGroups,
                                  String fileName) {
        Matcher tokens = TEMPLATE_TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (tokens.find()) {
            String name = tokens.group(1);
            boolean optional = tokens.group(2) != null;
            String value = optional
                    ? optionalGroup(matcher, name, fileName)
                    : requireGroup(matcher, name, fileName, "permit identity " + name);
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

    private String joinSplitDigits(String value) {
        return value == null ? "" : value.replaceAll("(?<=\\d)[ \\t]+(?=\\d)", "");
    }

    private String optionalGroup(Matcher matcher, String group, String fileName) {
        try {
            String value = matcher.group(group);
            return value == null || value.isBlank() ? "" : clean(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(fileName, "Profile references unknown regex group " + group);
        }
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
        return parseDate(value, formats, localeTag, fileName, field, null);
    }

    private LocalDate parseDate(String value,
                                List<String> formats,
                                String localeTag,
                                String fileName,
                                String field,
                                Integer fallbackYear) {
        Locale locale = localeTag == null || localeTag.isBlank()
                ? Locale.ENGLISH
                : Locale.forLanguageTag(localeTag);
        // A documented Qatar Executive permit prints OCT as OOCT on all rows.
        // Correct only that unambiguous month typo; never guess an arbitrary date.
        String normalizedValue = value.trim()
                .replaceFirst("(?iu)^([0-3]?\\d)OOCT(?=\\d{2}(?:\\d{2})?$)", "$1OCT")
                .replaceFirst("(?iu)^(\\d{1,2})(?:ST|ND|RD|TH)(?=\\s)", "$1")
                // Two Bamboo Airways permits print October as "Octorber".
                .replaceAll("(?iu)(?<=\\d\\s)Octorber(?=\\s\\d{4}$)", "October")
                // A recurring Air Busan form typo writes May as "Mau".
                .replaceAll("(?iu)(?<=\\d)Mau(?=\\s|\\d|$)", "May");
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
        if (fallbackYear != null && normalizedValue.matches("(?iu)\\d{1,2}(?:-|\\s*)[A-Z]{3}")) {
            return parseDate(normalizedValue + fallbackYear, acceptedFormats, localeTag,
                    fileName, field, null);
        }
        throw invalid(fileName, "Invalid " + field + ": " + value);
    }


    /** Some CAAV tables store equal start/end dates in one cell, e.g. 09APR09APR. */
    private String[] splitConcatenatedDateRange(String from, String to) {
        String normalizedFrom = from == null ? "" : from.trim();
        String normalizedTo = to == null ? "" : to.trim();
        if (normalizedFrom.equalsIgnoreCase(normalizedTo)) {
            Matcher matcher = Pattern.compile(
                    "(?iu)^(?<from>\\d{1,2}[A-Z]{3}(?:\\d{4}|\\d{2}))"
                            + "(?<to>\\d{1,2}[A-Z]{3}(?:\\d{4}|\\d{2}))$")
                    .matcher(normalizedFrom.replaceAll("\\s+", ""));
            if (matcher.matches()) {
                return new String[] {matcher.group("from"), matcher.group("to")};
            }
            matcher = Pattern.compile(
                    "(?iu)^(?<from>\\d{1,2}[A-Z]{3})(?<to>\\d{1,2}[A-Z]{3})$")
                    .matcher(normalizedFrom.replaceAll("\\s+", ""));
            if (matcher.matches()) {
                return new String[] {matcher.group("from"), matcher.group("to")};
            }
        }
        return new String[] {normalizedFrom, normalizedTo};
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
        String raw = value == null ? "" : value.trim()
                .replaceAll("(?iu)(?:^|[;\\s]+)\\d+\\.\\s*", ";");
        List<List<String>> alternatives = Arrays.stream(raw.split("(?m)[;\\r\\n]+"))
                .map(this::normalizeAirwayLine)
                .filter(line -> !line.isBlank())
                .map(line -> Arrays.stream(line.split("/"))
                        .map(String::trim)
                        .filter(token -> !token.isBlank())
                        .toList())
                .filter(tokens -> !tokens.isEmpty())
                .toList();
        return mergeAirwayAlternatives(alternatives);
    }

    /**
     * Merges all airway alternatives into one ordered, duplicate-free route.
     * Alternatives that share an airway are treated as one route component;
     * the order within every published line is preserved. When two branches
     * diverge after a shared prefix, the lexical order makes the result
     * deterministic (for example Q3 before Q5).
     */
    static String mergeAirwayAlternatives(List<List<String>> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return "";
        }
        List<List<Integer>> components = new ArrayList<>();
        for (int index = 0; index < alternatives.size(); index++) {
            int currentIndex = index;
            List<Integer> touching = new ArrayList<>();
            for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
                if (components.get(componentIndex).stream()
                        .anyMatch(existing -> sharesToken(alternatives.get(currentIndex), alternatives.get(existing)))) {
                    touching.add(componentIndex);
                }
            }
            if (touching.isEmpty()) {
                components.add(new ArrayList<>(List.of(index)));
            } else {
                int target = touching.getFirst();
                components.get(target).add(index);
                for (int touchingIndex = touching.size() - 1; touchingIndex >= 1; touchingIndex--) {
                    int source = touching.get(touchingIndex);
                    components.get(target).addAll(components.remove(source));
                }
            }
        }

        List<String> merged = new ArrayList<>();
        for (List<Integer> component : components) {
            merged.addAll(topologicalMerge(component, alternatives));
        }
        return String.join("/", new LinkedHashSet<>(merged));
    }

    private static boolean sharesToken(List<String> left, List<String> right) {
        return left.stream().anyMatch(new LinkedHashSet<>(right)::contains);
    }

    private static List<String> topologicalMerge(List<Integer> component,
                                                 List<List<String>> alternatives) {
        LinkedHashSet<String> nodes = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> edges = new LinkedHashMap<>();
        Map<String, Integer> firstPosition = new LinkedHashMap<>();
        for (int alternativeIndex : component) {
            List<String> line = alternatives.get(alternativeIndex);
            for (int position = 0; position < line.size(); position++) {
                String token = line.get(position);
                nodes.add(token);
                firstPosition.merge(token, position, Math::min);
                edges.computeIfAbsent(token, ignored -> new LinkedHashSet<>());
                if (position > 0) {
                    String previous = line.get(position - 1);
                    if (previous.equals(token)) {
                        continue;
                    }
                    edges.computeIfAbsent(previous, ignored -> new LinkedHashSet<>()).add(token);
                }
            }
        }
        Map<String, Integer> indegree = new LinkedHashMap<>();
        nodes.forEach(node -> indegree.put(node, 0));
        edges.values().forEach(targets -> targets.forEach(target -> indegree.merge(target, 1, Integer::sum)));

        List<String> result = new ArrayList<>();
        while (result.size() < nodes.size()) {
            List<String> available = nodes.stream()
                    .filter(node -> !result.contains(node) && indegree.get(node) == 0)
                    .sorted(Comparator.comparingInt((String node) -> firstPosition.getOrDefault(node, Integer.MAX_VALUE))
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
            if (available.isEmpty()) {
                // A contradictory source table should not make parsing hang;
                // append the remaining values deterministically.
                nodes.stream().filter(node -> !result.contains(node)).sorted().forEach(result::add);
                break;
            }
            String next = available.getFirst();
            result.add(next);
            edges.getOrDefault(next, new LinkedHashSet<>())
                    .forEach(target -> indegree.merge(target, -1, Integer::sum));
        }
        return result;
    }

    private String normalizeAirwayLine(String value) {
        String normalized = clean(value).toUpperCase(Locale.ROOT)
                .replaceAll("(?iu)\\s+OR\\s+", "/")
                .replaceAll("\\s*,\\s*", "/")
                .replaceAll("\\s*[-\\u2013\\u2014]\\s*", "/")
                .replaceAll("/{2,}", "/");
        LinkedHashSet<String> uniqueAirways = new LinkedHashSet<>();
        Arrays.stream(normalized.split("/"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .forEach(uniqueAirways::add);
        return String.join("/", uniqueAirways);
    }

    private String normalizeTime(String value,
                                 List<String> formats,
                                 String localeTag,
                                 String fileName,
                                 String field,
                                 boolean nullable,
                                 int rowNumber) {
        String cleanValue = clean(value);
        if (cleanValue.isBlank() && nullable) {
            return null;
        }
        // Parenthesized timetable offsets carry the same meaning as +1.
        cleanValue = cleanValue.replaceFirst("\\(\\s*\\+\\s*(?:1)?\\s*\\)$", "+1");
        // Forms use both ICAO-style "+1" and timetable-style "/1" to mark
        // an arrival on the following UTC day (for example 0045/1).
        boolean nextDay = cleanValue.matches(".*(?:\\+\\s*(?:1)?|/\\s*1)$");
        cleanValue = cleanValue.replaceFirst("\\s*(?:\\+\\s*(?:1)?|/\\s*1)$", "");
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
        throw invalid(fileName, "Schedule row " + rowNumber + " has invalid " + field
                + " format; detected " + field + " = " + value);
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
        return value == null ? "" : value
                .replace('\u00a0', ' ')
                .replaceAll("\\p{Cf}+", "")
                .replaceAll("\\s+", " ")
                .trim();
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

    private record ScheduleTableDifference(
            int tableIndex,
            List<String> header,
            Map<String, Integer> matchedColumns,
            List<String> missingFields) {
    }

    private record RouteRow(String from, String to, String airways) {
        boolean matches(String candidateFrom, String candidateTo) {
            return from.equals(candidateFrom) && to.equals(candidateTo);
        }
    }

    private record AircraftSource(String value, boolean defaulted) {
    }

    private record HeaderlessAircraft(String aircraftType, String registration) {
    }
}
