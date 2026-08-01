package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.common.training.PermitTrainingLayoutFingerprinter;
import vatm.aerosync.worker.model.PermitFieldDiagnostic;
import vatm.aerosync.worker.model.PermitParseWarning;
import vatm.aerosync.worker.model.PermitProfileCandidate;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LearnedPermitRuntimeParser {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            formatter("d/M/uuuu"), formatter("d-M-uuuu"),
            formatter("dMMMyy"), formatter("d-MMM-yy"),
            formatter("dMMMuuuu"), formatter("d-MMM-uuuu"),
            formatter("d MMM uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final ActiveLearnedPermitProfileCatalog catalog;
    private final LearnedPermitProfileReplayValidator extractor;
    private final AirportCodeCatalog airportCodeCatalog;

    public LearnedPermitRuntimeParser(
            ActiveLearnedPermitProfileCatalog catalog,
            LearnedPermitProfileReplayValidator extractor,
            AirportCodeCatalog airportCodeCatalog) {
        this.catalog = catalog;
        this.extractor = extractor;
        this.airportCodeCatalog = airportCodeCatalog;
    }

    public Optional<WordPermitParseResult> tryParse(
            WordPermitDocument document,
            String fileName) {
        PermitTrainingDocument trainingDocument = toTrainingDocument(document);
        String fingerprint = PermitTrainingLayoutFingerprinter.fingerprint(
                trainingDocument);
        List<ActiveLearnedPermitProfileCatalog.ActiveProfile> matches = catalog
                .activeProfiles().stream()
                .filter(profile -> fingerprint.equals(profile.layoutFingerprint()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw invalid(fileName,
                    "More than one active learned format matched this document; "
                            + "it was quarantined for operator review");
        }
        ActiveLearnedPermitProfileCatalog.ActiveProfile active =
                matches.getFirst();
        try {
            LearnedPermitProfileReplayValidator.ExtractionResult extraction =
                    extractor.extract(active.compiled(), trainingDocument);
            SchedulePermit permit = toPermit(
                    active.compiled(), extraction, document.rawContent());
            String profileId = "learned:" + active.compiled().profileKey();
            List<PermitFieldDiagnostic> fields = active.compiled().fields()
                    .stream()
                    .map(field -> new PermitFieldDiagnostic(
                            field.semanticField(), 1.0, profileId,
                            "LEARNED_MAPPING",
                            extraction.fields().get(field.semanticField())))
                    .toList();
            PermitProfileCandidate candidate = new PermitProfileCandidate(
                    profileId,
                    active.compiled().profileVersion(),
                    0,
                    1.0,
                    1,
                    1,
                    true,
                    !active.compiled().tables().isEmpty());
            return Optional.of(new WordPermitParseResult(
                    permit,
                    profileId,
                    active.compiled().profileVersion(),
                    1.0,
                    1.0,
                    true,
                    List.of(candidate),
                    fields,
                    List.of(new PermitParseWarning(
                            "LEARNED_PROFILE_REVIEW_REQUIRED",
                            "A learned format extracted this permit; operator review is required",
                            true))));
        } catch (RuntimeException exception) {
            throw invalid(fileName,
                    "The matching learned format could not safely read this document: "
                            + safeMessage(exception));
        }
    }

    private SchedulePermit toPermit(
            CompiledPermitTrainingProfile profile,
            LearnedPermitProfileReplayValidator.ExtractionResult extraction,
            String rawContent) {
        PermitTrainingProfileDefinition.Options options = profile.options();
        if (options == null) {
            throw new IllegalStateException("Learned format is missing permit defaults");
        }
        Map<String, String> fields = extraction.fields();
        String sourceNumber = required(fields, "permit.sourceNumber")
                .toUpperCase(Locale.ROOT);
        LocalDate permitDate = parseDate(required(fields, "permit.date"));
        String permitNumber = permitNumber(sourceNumber, options.permitType());
        String normalizedPermitId = normalizedPermitId(
                permitNumber, sourceNumber, permitDate, options);
        String operator = required(fields, "operator.icao")
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        String purpose = clean(fields.get("purpose"));
        if (purpose.isBlank()) {
            purpose = clean(options.flightType());
        }
        String resolvedPurpose = purpose;

        Map<String, String> routes = routes(extraction.tables(), options);
        AircraftDefaults aircraft = aircraft(extraction.tables());
        List<ScheduleFlight> flights = new ArrayList<>();
        extraction.tables().stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.SCHEDULE
                        || table.role()
                        == PermitTrainingProfileDefinition.TableRole.SUPPLEMENTAL_SCHEDULE)
                .flatMap(table -> table.rows().stream())
                .forEach(row -> flights.add(toFlight(
                        row, operator, resolvedPurpose, routes, aircraft, options)));
        if (flights.isEmpty()) {
            throw new IllegalStateException("Learned format produced no schedule rows");
        }
        return new SchedulePermit(
                sourceNumber,
                normalizedPermitId,
                permitNumber,
                clean(options.authorId()).toUpperCase(Locale.ROOT),
                clean(options.permitType()).toUpperCase(Locale.ROOT),
                clean(options.version()).toUpperCase(Locale.ROOT),
                clean(options.season()).toUpperCase(Locale.ROOT),
                permitDate,
                operator,
                blankToNull(fields.get("reference")),
                options.validHours() == null ? 0 : options.validHours(),
                blankToNull(fields.get("billing.address")),
                clean(options.flightType()).toUpperCase(Locale.ROOT),
                options.allowIataAirports(),
                options.emptyAirwaysAllowed(),
                true,
                rawContent,
                flights);
    }

    private ScheduleFlight toFlight(
            Map<String, String> row,
            String operator,
            String purpose,
            Map<String, String> routes,
            AircraftDefaults aircraft,
            PermitTrainingProfileDefinition.Options options) {
        LocalDate fromDate = parseDate(required(row, "effectiveFrom"));
        LocalDate toDate = parseDate(required(row, "effectiveTo"));
        String from = airport(required(row, "fromAirport"), options);
        String to = airport(required(row, "toAirport"), options);
        String type = clean(row.get("aircraftType"));
        if (type.isBlank()) {
            type = aircraft.type();
        }
        String registration = clean(row.get("registration"));
        if (registration.isBlank()) {
            registration = aircraft.registration();
        }
        String via = clean(row.get("airways"));
        if (via.isBlank()) {
            via = routes.getOrDefault(from + "-" + to, "");
        }
        String serviceDays = serviceDays(required(row, "serviceDays"));
        if (fromDate.equals(toDate)) {
            char[] days = "0000000".toCharArray();
            int index = fromDate.getDayOfWeek().getValue() - 1;
            days[index] = (char) ('1' + index);
            serviceDays = new String(days);
        }
        String flightNumber = flightNumber(required(row, "flightNumber"), operator);
        String remark = clean(row.get("remark"));
        if (remark.isBlank()) {
            remark = (purpose + " " + type).trim();
        }
        return new ScheduleFlight(
                purpose,
                0L,
                null,
                flightNumber,
                blankToNull(registration),
                serviceDays,
                from,
                to,
                time(required(row, "etd")),
                blankToNull(time(row.get("eta"))),
                blankToNull(via),
                fromDate,
                toDate,
                blankToNull(remark),
                blankToNull(type));
    }

    private Map<String, String> routes(
            List<LearnedPermitProfileReplayValidator.TableExtraction> tables,
            PermitTrainingProfileDefinition.Options options) {
        Map<String, String> result = new LinkedHashMap<>();
        tables.stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.ROUTE)
                .flatMap(table -> table.rows().stream())
                .forEach(row -> {
                    String[] sector = clean(row.get("sector"))
                            .toUpperCase(Locale.ROOT)
                            .split("\\s*[-\\u2013\\u2014]\\s*", 2);
                    String airways = clean(row.get("airways"));
                    if (sector.length == 2 && !airways.isBlank()) {
                        result.put(airport(sector[0], options) + "-"
                                + airport(sector[1], options), airways);
                    }
                });
        return result;
    }

    private AircraftDefaults aircraft(
            List<LearnedPermitProfileReplayValidator.TableExtraction> tables) {
        return tables.stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.AIRCRAFT)
                .flatMap(table -> table.rows().stream())
                .filter(row -> !clean(row.get("aircraftType")).isBlank())
                .findFirst()
                .map(row -> new AircraftDefaults(
                        clean(row.get("aircraftType")),
                        clean(row.get("registrationMarks"))))
                .orElse(new AircraftDefaults("", ""));
    }

    private PermitTrainingDocument toTrainingDocument(WordPermitDocument document) {
        List<PermitTrainingDocument.Table> tables = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
            List<PermitTrainingDocument.Row> rows = new ArrayList<>();
            List<List<String>> sourceRows = document.tables().get(tableIndex);
            for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
                List<PermitTrainingDocument.Cell> cells = new ArrayList<>();
                for (int columnIndex = 0;
                     columnIndex < sourceRows.get(rowIndex).size(); columnIndex++) {
                    cells.add(new PermitTrainingDocument.Cell(
                            "table-%d-row-%d-cell-%d".formatted(
                                    tableIndex, rowIndex, columnIndex),
                            rowIndex,
                            columnIndex,
                            sourceRows.get(rowIndex).get(columnIndex)));
                }
                rows.add(new PermitTrainingDocument.Row(rowIndex, cells));
            }
            tables.add(new PermitTrainingDocument.Table(
                    tableIndex,
                    document.tableContexts().get(tableIndex),
                    rows));
        }
        return new PermitTrainingDocument(
                document.paragraphText(), document.tableText(),
                document.rawContent(), tables, document.authoredDate());
    }

    private String normalizedPermitId(
            String permitNumber,
            String sourceNumber,
            LocalDate permitDate,
            PermitTrainingProfileDefinition.Options options) {
        Matcher yearMatcher = YEAR.matcher(sourceNumber);
        String year = yearMatcher.find()
                ? yearMatcher.group(1) : Integer.toString(permitDate.getYear());
        String number = permitNumber.matches("\\d+")
                ? String.format(Locale.ROOT, "%05d", Long.parseLong(permitNumber))
                : permitNumber;
        return "%s %s/%s/%s/%s".formatted(
                clean(options.permitType()).toUpperCase(Locale.ROOT),
                number,
                clean(options.season()).toUpperCase(Locale.ROOT),
                clean(options.authorId()).toUpperCase(Locale.ROOT),
                year);
    }

    private String permitNumber(String sourceNumber, String permitType) {
        Matcher typed = Pattern.compile("(?iu)"
                        + Pattern.quote(clean(permitType))
                        + "\\D*(\\d{1,10})")
                .matcher(sourceNumber);
        if (typed.find()) {
            return stripLeadingZeros(typed.group(1));
        }
        Matcher any = Pattern.compile("\\d{1,10}").matcher(sourceNumber);
        if (any.find()) {
            return stripLeadingZeros(any.group());
        }
        throw new IllegalStateException("Permit number contains no numeric identifier");
    }

    private String stripLeadingZeros(String value) {
        String stripped = value.replaceFirst("^0+(?!$)", "");
        return stripped.isBlank() ? "0" : stripped;
    }

    private String flightNumber(String value, String operator) {
        String compact = clean(value).replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        Matcher iata = Pattern.compile("^([A-Z]{2})(\\d.*)$").matcher(compact);
        return iata.matches() && operator.matches("[A-Z]{3}")
                ? operator + iata.group(2) : compact;
    }

    private String airport(
            String value,
            PermitTrainingProfileDefinition.Options options) {
        return options.allowIataAirports()
                ? airportCodeCatalog.canonicalize(value)
                : airportCodeCatalog.normalize(value);
    }

    private String serviceDays(String value) {
        String compact = clean(value).replaceAll("\\s+", "");
        boolean[] active = new boolean[7];
        compact.chars().filter(character -> character >= '1' && character <= '7')
                .forEach(character -> active[character - '1'] = true);
        StringBuilder result = new StringBuilder(7);
        for (int index = 0; index < active.length; index++) {
            result.append(active[index] ? (char) ('1' + index) : '0');
        }
        if (result.toString().equals("0000000")) {
            throw new IllegalStateException("Days of service contain no operating day");
        }
        return result.toString();
    }

    private LocalDate parseDate(String value) {
        for (DateTimeFormatter dateFormat : DATE_FORMATS) {
            try {
                return LocalDate.parse(clean(value).replaceAll("\\s+", ""), dateFormat);
            } catch (DateTimeParseException ignored) {
                // Try the next safe date format.
            }
        }
        throw new IllegalStateException("Unsupported date: " + value);
    }

    private String time(String value) {
        String compact = clean(value).replace(":", "")
                .replaceAll("\\s+", "");
        return compact.isBlank() ? "" : compact;
    }

    private String required(Map<String, String> values, String field) {
        String value = clean(values.get(field));
        if (value.isBlank()) {
            throw new IllegalStateException("Required learned field is missing: " + field);
        }
        return value;
    }

    private String blankToNull(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned;
    }

    private String clean(String value) {
        return value == null ? "" : PermitTextNormalizer.clean(value);
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    private record AircraftDefaults(String type, String registration) {
    }
}
