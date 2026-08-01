package vatm.aerosync.api.service;

import org.springframework.stereotype.Component;
import vatm.aerosync.api.dto.PermitTrainingWorkflowResponse;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;

import java.text.Normalizer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PermitTrainingMappingAssistant {

    private static final double AUTO_CONFIDENCE = 0.95;
    private static final Pattern SPACE = Pattern.compile("\\s+");
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

    private static final Map<String, List<String>> SCHEDULE_ALIASES = Map.ofEntries(
            Map.entry("flightNumber", List.of("flight number", "flightnumber", "flt no", "callsign", "số hiệu chuyến bay")),
            Map.entry("effectiveFrom", List.of("effective from", "effectivefrom", "valid from", "begin date", "hiệu lực từ")),
            Map.entry("effectiveTo", List.of("effective to", "effectiveto", "valid to", "end date", "hiệu lực đến")),
            Map.entry("serviceDays", List.of("days of service", "days of services", "day(s) of services", "service days", "ngày trong tuần")),
            Map.entry("fromAirport", List.of("departure airport", "departure", "from airport", "from", "sân bay cất cánh")),
            Map.entry("etd", List.of("etd", "departure time", "giờ dự kiến cất cánh")),
            Map.entry("toAirport", List.of("arrival airport", "arrival", "to airport", "to", "sân bay hạ cánh")),
            Map.entry("eta", List.of("eta", "arrival time", "giờ dự kiến hạ cánh")),
            Map.entry("aircraftType", List.of("aircraft type", "acft type", "loại tàu bay")),
            Map.entry("airways", List.of("airways", "route")),
            Map.entry("originalPermit", List.of("original permit", "previous permit")),
            Map.entry("remark", List.of("remark", "remarks", "note")));

    private static final Map<String, List<String>> ROUTE_ALIASES = Map.of(
            "sector", List.of("sector", "route sector"),
            "airways", List.of("airways", "route", "routing"));
    private static final Map<String, List<String>> AIRCRAFT_ALIASES = Map.of(
            "aircraftType", List.of("aircraft type", "acft type"),
            "registrationMarks", List.of(
                    "registration mark", "registration marks", "registration"));

    private static final Set<String> REQUIRED_SCHEDULE = Set.of(
            "flightNumber", "effectiveFrom", "effectiveTo", "serviceDays",
            "fromAirport", "etd", "toAirport");

    public Assistance suggest(
            PermitTrainingDocument document,
            PermitReviewSnapshot expected,
            String displayName,
            String family) {
        if (document == null || expected == null) {
            return new Assistance(
                    emptyDefinition(displayName, family), List.of(),
                    List.of("CORRECT_PERMIT_REQUIRED"));
        }

        List<PermitTrainingProfileDefinition.FieldMapping> fields =
                new ArrayList<>();
        List<PermitTrainingWorkflowResponse.Suggestion> suggestions =
                new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        addField(document, fields, suggestions, unresolved,
                "permit.sourceNumber", "Permit number",
                expected.sourcePermitNumber(), true, false);
        addField(document, fields, suggestions, unresolved,
                "permit.date", "Permit date",
                expected.permitDate() == null
                        ? null : expected.permitDate().toString(),
                true, true);
        addField(document, fields, suggestions, unresolved,
                "operator.icao", "Operator ICAO",
                expected.operatorId(), true, false);
        addField(document, fields, suggestions, unresolved,
                "billing.address", "Billing address",
                expected.billingAddress(), false, false);
        addField(document, fields, suggestions, unresolved,
                "reference", "Reference",
                expected.reference(), false, false);
        String purpose = expected.flights().isEmpty()
                ? null : expected.flights().getFirst().purposeId();
        addField(document, fields, suggestions, unresolved,
                "purpose", "Purpose", purpose, false, false);

        ScheduleSuggestion schedule = schedule(document);
        if (schedule == null) {
            REQUIRED_SCHEDULE.forEach(column ->
                    unresolved.add("schedule." + column));
        } else {
            schedule.columns().forEach((semantic, cell) -> suggestions.add(
                    new PermitTrainingWorkflowResponse.Suggestion(
                            "schedule." + semantic,
                            scheduleLabel(semantic),
                            "CELL",
                            cell.id(),
                            cell.value(),
                            null,
                            schedule.confidence(),
                            schedule.confidence() >= AUTO_CONFIDENCE,
                            schedule.confidence() >= AUTO_CONFIDENCE
                                    ? "Schedule header matched automatically"
                                    : "Confirm this schedule header")));
            REQUIRED_SCHEDULE.stream()
                    .filter(column -> !schedule.columns().containsKey(column))
                    .forEach(column -> unresolved.add("schedule." + column));
            if (schedule.confidence() < AUTO_CONFIDENCE) {
                schedule.columns().keySet().forEach(column ->
                        unresolved.add("confirm.schedule." + column));
            }
        }

        List<PermitTrainingProfileDefinition.TableMapping> tables =
                new ArrayList<>();
        if (schedule != null && !schedule.columns().isEmpty()) {
            tables.add(tableMapping(
                    PermitTrainingProfileDefinition.TableRole.SCHEDULE,
                    schedule));
        }

        boolean expectedAirways = expected.flights().stream()
                .anyMatch(flight -> flight.via() != null
                        && !flight.via().isBlank());
        TableSuggestion route = table(
                document, ROUTE_ALIASES, Set.of("sector", "airways"));
        if (route != null && expectedAirways) {
            tables.add(tableMapping(
                    PermitTrainingProfileDefinition.TableRole.ROUTE, route));
        } else if (expectedAirways && !expected.emptyAirwaysAllowed()) {
            unresolved.add("route.airways");
        }

        boolean scheduleHasAircraft = schedule != null
                && schedule.columns().containsKey("aircraftType");
        boolean expectedAircraft = expected.flights().stream()
                .anyMatch(flight -> flight.sourceAircraftType() != null
                        && !flight.sourceAircraftType().isBlank());
        TableSuggestion aircraft = table(
                document, AIRCRAFT_ALIASES, Set.of("aircraftType"));
        if (!scheduleHasAircraft && aircraft != null && expectedAircraft) {
            tables.add(tableMapping(
                    PermitTrainingProfileDefinition.TableRole.AIRCRAFT,
                    aircraft));
        } else if (!scheduleHasAircraft && expectedAircraft) {
            unresolved.add("aircraft.aircraftType");
        }

        if (expected.flights().stream().anyMatch(flight ->
                flight.eta() != null && !flight.eta().isBlank())
                && (schedule == null
                || !schedule.columns().containsKey("eta"))) {
            unresolved.add("schedule.eta");
        }

        PermitTrainingProfileDefinition.Options options =
                new PermitTrainingProfileDefinition.Options(
                        expected.authorId(),
                        expected.permitType(),
                        expected.version(),
                        expected.season(),
                        expected.validHours(),
                        expected.flightType(),
                        expected.iataAirportsAllowed(),
                        expected.emptyAirwaysAllowed(),
                        true);
        return new Assistance(
                new PermitTrainingProfileDefinition(
                        1, displayName, family, fields, List.copyOf(tables), options),
                suggestions,
                unresolved.stream().distinct().toList());
    }

    private void addField(
            PermitTrainingDocument document,
            List<PermitTrainingProfileDefinition.FieldMapping> fields,
            List<PermitTrainingWorkflowResponse.Suggestion> suggestions,
            List<String> unresolved,
            String semantic,
            String label,
            String expected,
            boolean required,
            boolean date) {
        if (expected == null || expected.isBlank()) {
            if (required) {
                unresolved.add(semantic);
            }
            return;
        }
        Match match = match(document, expected, date);
        if (match == null) {
            unresolved.add(semantic);
            return;
        }
        PermitTrainingProfileDefinition.SourceKind kind = match.cellId() == null
                ? PermitTrainingProfileDefinition.SourceKind.TEXT
                : PermitTrainingProfileDefinition.SourceKind.CELL;
        fields.add(new PermitTrainingProfileDefinition.FieldMapping(
                semantic,
                kind,
                match.cellId(),
                kind == PermitTrainingProfileDefinition.SourceKind.TEXT
                        ? match.selectedText() : null,
                expected,
                required));
        boolean automatic = match.confidence() >= AUTO_CONFIDENCE;
        suggestions.add(new PermitTrainingWorkflowResponse.Suggestion(
                semantic,
                label,
                kind.name(),
                match.cellId(),
                match.selectedText(),
                expected,
                match.confidence(),
                automatic,
                automatic ? "Matched automatically" : "Confirm this value"));
        if (!automatic) {
            unresolved.add("confirm." + semantic);
        }
    }

    private Match match(
            PermitTrainingDocument document,
            String expected,
            boolean date) {
        String normalizedExpected = normalize(expected);
        List<PermitTrainingDocument.Cell> cells = document.tables().stream()
                .flatMap(table -> table.rows().stream())
                .flatMap(row -> row.cells().stream())
                .filter(cell -> containsValue(cell.value(), normalizedExpected, date))
                .toList();
        if (cells.size() == 1) {
            PermitTrainingDocument.Cell cell = cells.getFirst();
            return new Match(cell.id(), cell.value(), 0.99);
        }
        List<String> lines = safe(document.rawContent()).lines()
                .map(String::trim)
                .filter(line -> containsValue(line, normalizedExpected, date))
                .distinct()
                .toList();
        if (lines.size() == 1) {
            return new Match(null, lines.getFirst(), 0.96);
        }
        return null;
    }

    private boolean containsValue(
            String candidate,
            String normalizedExpected,
            boolean date) {
        if (date) {
            LocalDate expected;
            try {
                expected = LocalDate.parse(normalizedExpected);
            } catch (DateTimeParseException exception) {
                return false;
            }
            Matcher matcher = DATE_TEXT.matcher(safe(candidate));
            while (matcher.find()) {
                LocalDate parsed = parseDate(matcher.group());
                if (expected.equals(parsed)) {
                    return true;
                }
            }
            return false;
        }
        String normalized = normalize(candidate);
        if (normalizedExpected.isBlank()) {
            return false;
        }
        if (normalized.equals(normalizedExpected)) {
            return true;
        }
        if (normalizedExpected.length() <= 3) {
            return Pattern.compile("(?iu)(?<![A-Z0-9])"
                            + Pattern.quote(normalizedExpected)
                            + "(?![A-Z0-9])")
                    .matcher(normalized).find();
        }
        return normalized.contains(normalizedExpected);
    }

    private ScheduleSuggestion schedule(PermitTrainingDocument document) {
        ScheduleSuggestion best = null;
        for (PermitTrainingDocument.Table table : document.tables()) {
            for (PermitTrainingDocument.Row row : table.rows()) {
                if (row.index() + 1 >= table.rows().size()) {
                    continue;
                }
                Map<String, PermitTrainingDocument.Cell> columns =
                        new LinkedHashMap<>();
                for (PermitTrainingDocument.Cell cell : row.cells()) {
                    String semantic = semantic(cell.value(), SCHEDULE_ALIASES);
                    if (semantic != null && !columns.containsKey(semantic)) {
                        columns.put(semantic, cell);
                    }
                }
                long required = columns.keySet().stream()
                        .filter(REQUIRED_SCHEDULE::contains).count();
                double confidence = required / (double) REQUIRED_SCHEDULE.size();
                ScheduleSuggestion candidate = new ScheduleSuggestion(
                        table.index(), row.index() + 1, columns, confidence);
                if (best == null || candidate.confidence() > best.confidence()) {
                    best = candidate;
                }
            }
        }
        return best == null || best.confidence() < 0.50 ? null : best;
    }

    private String semantic(
            String value,
            Map<String, List<String>> aliases) {
        String normalized = normalize(value);
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            for (String alias : entry.getValue()) {
                String normalizedAlias = normalize(alias);
                if ((normalized.equals(normalizedAlias)
                        || normalized.contains(normalizedAlias))
                        && normalizedAlias.length() > bestLength) {
                    best = entry.getKey();
                    bestLength = normalizedAlias.length();
                }
            }
        }
        return best;
    }

    private TableSuggestion table(
            PermitTrainingDocument document,
            Map<String, List<String>> aliases,
            Set<String> required) {
        TableSuggestion best = null;
        for (PermitTrainingDocument.Table table : document.tables()) {
            for (PermitTrainingDocument.Row row : table.rows()) {
                if (row.index() + 1 >= table.rows().size()) {
                    continue;
                }
                Map<String, PermitTrainingDocument.Cell> columns =
                        new LinkedHashMap<>();
                for (PermitTrainingDocument.Cell cell : row.cells()) {
                    String meaning = semantic(cell.value(), aliases);
                    if (meaning != null) {
                        columns.putIfAbsent(meaning, cell);
                    }
                }
                long matched = required.stream()
                        .filter(columns::containsKey).count();
                double confidence = matched / (double) required.size();
                TableSuggestion candidate = new TableSuggestion(
                        table.index(), row.index() + 1, columns, confidence);
                if (best == null || candidate.confidence() > best.confidence()) {
                    best = candidate;
                }
            }
        }
        return best == null || best.confidence() < 0.75 ? null : best;
    }

    private PermitTrainingProfileDefinition.TableMapping tableMapping(
            PermitTrainingProfileDefinition.TableRole role,
            TableSuggestion suggestion) {
        return new PermitTrainingProfileDefinition.TableMapping(
                role,
                suggestion.tableIndex(),
                suggestion.dataStartRowIndex(),
                suggestion.columns().entrySet().stream()
                        .collect(LinkedHashMap::new,
                                (map, item) -> map.put(
                                        item.getKey(), item.getValue().id()),
                                LinkedHashMap::putAll));
    }

    private PermitTrainingProfileDefinition.TableMapping tableMapping(
            PermitTrainingProfileDefinition.TableRole role,
            ScheduleSuggestion suggestion) {
        return new PermitTrainingProfileDefinition.TableMapping(
                role,
                suggestion.tableIndex(),
                suggestion.dataStartRowIndex(),
                suggestion.columns().entrySet().stream()
                        .collect(LinkedHashMap::new,
                                (map, item) -> map.put(
                                        item.getKey(), item.getValue().id()),
                                LinkedHashMap::putAll));
    }

    private String scheduleLabel(String semantic) {
        return switch (semantic) {
            case "flightNumber" -> "Flight number";
            case "effectiveFrom" -> "Effective from";
            case "effectiveTo" -> "Effective to";
            case "serviceDays" -> "Days of service";
            case "fromAirport" -> "Departure airport";
            case "etd" -> "ETD";
            case "toAirport" -> "Arrival airport";
            case "eta" -> "ETA";
            case "aircraftType" -> "Aircraft type";
            case "originalPermit" -> "Original permit";
            case "remark" -> "Remark";
            default -> semantic;
        };
    }

    private PermitTrainingProfileDefinition emptyDefinition(
            String displayName,
            String family) {
        return new PermitTrainingProfileDefinition(
                1, displayName, family, List.of(), List.of(), null);
    }

    private static DateTimeFormatter dateFormatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    private LocalDate parseDate(String value) {
        String cleaned = safe(value).replaceAll("\\s+", "").trim();
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported permit date format.
            }
        }
        return null;
    }

    private String normalize(String value) {
        return SPACE.matcher(Normalizer.normalize(
                        safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT))
                .replaceAll(" ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record Assistance(
            PermitTrainingProfileDefinition definition,
            List<PermitTrainingWorkflowResponse.Suggestion> suggestions,
            List<String> unresolved) {
    }

    private record Match(
            String cellId,
            String selectedText,
            double confidence) {
    }

    private record ScheduleSuggestion(
            int tableIndex,
            int dataStartRowIndex,
            Map<String, PermitTrainingDocument.Cell> columns,
            double confidence) {
    }

    private record TableSuggestion(
            int tableIndex,
            int dataStartRowIndex,
            Map<String, PermitTrainingDocument.Cell> columns,
            double confidence) {
    }
}
