package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.atfm.AtfmAircraftTypeResolver;
import vatm.aerosync.worker.atfm.AtfmAirportCodeResolver;
import vatm.aerosync.worker.atfm.AtfmViaResolver;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ScheduleFlight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.regex.Pattern;

/** Opt-in, SELECT-only audit; never invokes import, reconciliation, archiving or replay. */
class AtfmAircraftRouteAuditTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> aircraftResults = new HashMap<>();
    private final Map<String, String> routeResults = new HashMap<>();
    private final Map<String, String> airportResults = new HashMap<>();
    private static final Pattern WORD = Pattern.compile("(?i).*\\.docx?(?:_[a-f0-9]+)?$");
    private static final Pattern IDENTITY = Pattern.compile("(?iu)\\b(?:LD|OF|O\\s*/\\s*F|QLB)\\s*[-–—:/ ]*\\d");

    @Test
    void auditConfiguredArchiveAgainstLiveReferenceData() throws Exception {
        String configured = System.getProperty("permit.reference.audit.dir");
        Assumptions.assumeTrue(configured != null, "Set -Dpermit.reference.audit.dir for the live read-only audit");
        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl(required("APP_ATFM_DATASOURCE_URL"));
        properties.setUsername(required("APP_ATFM_DATASOURCE_USERNAME"));
        properties.setPassword(required("APP_ATFM_DATASOURCE_PASSWORD"));
        Path output = Path.of(System.getProperty("permit.reference.audit.report", "target/reference-audit"));
        Files.createDirectories(output.resolve("documents"));
        Files.createDirectories(output.resolve("copies"));
        List<Path> files;
        try (var paths = Files.walk(Path.of(configured))) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> WORD.matcher(path.getFileName().toString()).matches())
                    .filter(path -> !path.getFileName().toString().startsWith("~$"))
                    .sorted().toList();
        }
        var reader = new WordPermitDocumentReader();
        var catalog = new DocxPermitProfileCatalog();
        var detector = new WordPermitFormatDetector(catalog);
        var parser = new DocxSchedulePermitParser(reader, detector);
        var aliases = new AircraftTypeCatalog();
        var aircraft = new AtfmAircraftTypeResolver(properties);
        var airports = new AtfmAirportCodeResolver();
        var routes = new AtfmViaResolver();
        Map<String, Long> counters = new TreeMap<>();
        Map<String, Long> carriers = new TreeMap<>();
        Map<String, Long> profiles = new TreeMap<>();
        Set<String> hashes = new HashSet<>();
        int seen = 0;
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
             var records = Files.newBufferedWriter(output.resolve("audit.jsonl"), StandardCharsets.UTF_8);
             var inventory = Files.newBufferedWriter(output.resolve("inventory.jsonl"), StandardCharsets.UTF_8)) {
            connection.setReadOnly(true);
            // All SQL used here and in the reference resolvers is SELECT-only.
            for (String table : List.of("M_CRAFT_TYPE", "M_AIRPORT_ROUTE", "M_AERO")) {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    counters.put("dictionary_" + table, rows.getLong(1));
                }
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT CRAFT_ID, MA, SOHIEU FROM M_CRAFT_TYPE");
                 var dictionary = Files.newBufferedWriter(output.resolve("aircraft-dictionary.jsonl"))) {
                while (rows.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (String column : List.of("CRAFT_ID", "MA", "SOHIEU")) {
                        item.put(column, rows.getObject(column));
                    }
                    dictionary.write(json.writeValueAsString(item));
                    dictionary.newLine();
                }
            }
            System.out.println("REFERENCE AUDIT START files=" + files.size() + " dictionaries=" + counters);
            for (Path file : files) {
                seen++;
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (Exception error) {
                    increment(counters, "unreadable");
                    continue;
                }
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                inventory.write(json.writeValueAsString(Map.of("file", file.toString(), "sha256", hash)));
                inventory.newLine();
                if (!hashes.add(hash)) {
                    increment(counters, "duplicates");
                    continue;
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("file", file.toString());
                record.put("sha256", hash);
                WordPermitDocument document = null;
                try {
                    Path input = file;
                    if (file.getFileName().toString().matches("(?i).*\\.docx?_[a-f0-9]+$")) {
                        String ext = file.getFileName().toString().toLowerCase(Locale.ROOT).contains(".docx_") ? ".docx" : ".doc";
                        input = output.resolve("copies").resolve(hash + ext);
                        Files.write(input, bytes);
                        record.put("restoredExtensionForAudit", true);
                    }
                    document = reader.read(input);
                    record.put("labeledCarrier", labeledCarrier(document.rawContent()));
                    if (!IDENTITY.matcher(document.rawContent()).find()) {
                        record.put("status", "NO_PERMIT_IDENTITY");
                        increment(counters, "no_permit_identity");
                    } else {
                        var profile = detector.detect(document, file.getFileName().toString());
                        record.put("profile", profile.id());
                        var permit = parser.parse(document, file.getFileName().toString());
                        record.put("carrier", permit.operatorId());
                        record.put("permit", permit.normalizedPermitId());
                        record.put("revision", permit.revision());
                        record.put("reviewOnly", permit.reviewOnly());
                        record.put("flightCount", permit.flights().size());
                        record.put("originalFlightCount", permit.originalFlights().size());
                        increment(carriers, permit.operatorId());
                        increment(profiles, profile.id());
                        increment(counters, "parsed_documents");
                        counters.merge("flights", (long) permit.flights().size(), Long::sum);
                        Map<String, Map<String, Object>> findings = new LinkedHashMap<>();
                        for (ScheduleFlight flight : permit.flights()) {
                            String source = AircraftTypeNotation.normalize(flight.sourceAircraftType());
                            String aircraftResult;
                            if (source == null || source.isBlank()) {
                                aircraftResult = "AIRCRAFT_SOURCE_EMPTY";
                            } else {
                                aircraftResult = aircraftResults.get(source);
                                if (aircraftResult == null) {
                                    try {
                                        var resolved = aircraft.resolve(aliases.candidates(source));
                                        aircraftResult = "OK:" + resolved.craftId();
                                    } catch (AtfmAircraftTypeResolver.AircraftTypeNotFoundException error) {
                                        aircraftResult = "AIRCRAFT_NOT_FOUND: " + error.getMessage();
                                    } catch (AtfmAircraftTypeResolver.AmbiguousAircraftTypeException error) {
                                        aircraftResult = "AIRCRAFT_AMBIGUOUS: " + error.getMessage();
                                    }
                                    aircraftResults.put(source, aircraftResult);
                                }
                            }
                            if (aircraftResult.startsWith("OK:")) {
                                increment(counters, "aircraft_resolved_rows");
                            } else {
                                add(findings, "aircraft", aircraftResult, flight, source);
                                increment(counters, aircraftResult.split(":")[0]);
                            }
                            String routeKey = flight.fromAirport() + "|" + flight.toAirport() + "|" + Objects.toString(flight.via(), "");
                            String routeResult = routeResults.get(routeKey);
                            if (routeResult == null) {
                                try {
                                    String from = airport(connection, airports, flight.fromAirport());
                                    String to = airport(connection, airports, flight.toAirport());
                                    routeResult = "OK:" + routes.resolve(connection, from, to, permit.operatorId(), flight.via());
                                } catch (vatm.aerosync.worker.atfm.AtfmDocumentRouteException error) {
                                    routeResult = "ROUTE_UNREADABLE: " + error.getMessage();
                                } catch (vatm.aerosync.worker.atfm.AtfmReferenceDataException error) {
                                    routeResult = "ROUTE_NOT_FOUND: " + error.getMessage();
                                }
                                routeResults.put(routeKey, routeResult);
                            }
                            if (routeResult.startsWith("OK:")) {
                                increment(counters, "route_resolved_rows");
                            } else {
                                add(findings, "route", routeResult, flight, flight.via());
                                increment(counters, routeResult.split(":")[0]);
                            }
                            if (flight.via() == null || flight.via().isBlank()) {
                                String evidence = routeEvidence(document.rawContent(), flight);
                                if (!evidence.isBlank()) {
                                    add(findings, "route-extraction", "SOURCE_ROUTE_PRESENT_BUT_EMPTY", flight, evidence);
                                    increment(counters, "SOURCE_ROUTE_PRESENT_BUT_EMPTY");
                                }
                            }
                        }
                        record.put("status", findings.isEmpty() ? "RESOLVED" : "FINDINGS");
                        record.put("findings", findings.values());
                        if (!findings.isEmpty()) {
                            Files.writeString(output.resolve("documents").resolve(hash + ".txt"),
                                    "SOURCE: " + file + "\n" + document.rawContent(), StandardCharsets.UTF_8);
                            increment(counters, "documents_with_findings");
                        }
                    }
                } catch (NonPermitDocumentException error) {
                    record.put("status", "NON_PERMIT");
                    increment(counters, "non_permit");
                } catch (Exception error) {
                    record.put("status", "PARSE_FAILURE");
                    record.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
                    increment(counters, "parse_failures");
                }
                records.write(json.writeValueAsString(record));
                records.newLine();
                if (hashes.size() % 100 == 0) {
                    records.flush();
                    inventory.flush();
                    System.out.println("REFERENCE AUDIT PROGRESS files=" + seen + " unique=" + hashes.size() + " parsed=" + counters.get("parsed_documents"));
                }
            }
        }
        counters.put("scanned_files", (long) files.size());
        counters.put("unique_documents", (long) hashes.size());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("counts", counters);
        summary.put("carriers", carriers);
        summary.put("profiles", profiles);
        summary.put("untestedProfiles", catalog.profiles().stream().map(DocxPermitFormatProfile::id)
                .filter(id -> !profiles.containsKey(id)).sorted().toList());
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), summary);
        System.out.println("REFERENCE AUDIT COMPLETE " + counters + " carriers=" + carriers.size());
    }

    private String airport(Connection connection, AtfmAirportCodeResolver resolver, String source) throws Exception {
        if (!airportResults.containsKey(source)) {
            airportResults.put(source, resolver.resolve(connection, source));
        }
        return airportResults.get(source);
    }

    private void add(Map<String, Map<String, Object>> findings, String field, String error,
                     ScheduleFlight flight, String source) {
        String key = field + "|" + error + "|" + source + "|" + flight.fromAirport() + "|" + flight.toAirport();
        Map<String, Object> item = findings.get(key);
        if (item == null) {
            item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("error", error);
            item.put("source", source);
            item.put("flight", flight.flightNumber());
            item.put("from", flight.fromAirport());
            item.put("to", flight.toAirport());
            item.put("rowCount", 0);
            findings.put(key, item);
        }
        item.put("rowCount", (int) item.get("rowCount") + 1);
    }

    private String routeEvidence(String text, ScheduleFlight flight) {
        Pattern pair = Pattern.compile("(?iu)" + Pattern.quote(flight.fromAirport())
                + "\\s*[-–—/]\\s*" + Pattern.quote(flight.toAirport()) + "\\s*[:|–—-]\\s*([^\\n]+)");
        var match = pair.matcher(text);
        while (match.find()) {
            if (Pattern.compile("(?i)\\b(?:[A-Z]{1,2}\\d{1,4}|DCT)\\b").matcher(match.group(1)).find()) {
                return match.group().substring(0, Math.min(350, match.group().length()));
            }
        }
        return "";
    }

    private String labeledCarrier(String text) {
        var match = Pattern.compile("(?iu)(?:ICAO\\s*(?:CODE)?|Mã\\s*ICAO)\\s*:\\s*([A-Z0-9]{3})\\b").matcher(text);
        return match.find() ? match.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private void increment(Map<String, Long> counts, String key) {
        counts.merge(Objects.toString(key, "UNKNOWN"), 1L, Long::sum);
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
