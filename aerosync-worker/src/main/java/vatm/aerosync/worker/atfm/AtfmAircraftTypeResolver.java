package vatm.aerosync.worker.atfm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class AtfmAircraftTypeResolver {

    private static final String LOAD_AIRCRAFT_TYPES_SQL = """
            SELECT CRAFT_ID, MA, SOHIEU, SOHIEU2, TAITRONG
              FROM M_CRAFT_TYPE
             WHERE CRAFT_ID IS NOT NULL
             ORDER BY CRAFT_ID
            """;

    private final AtfmDatabaseProperties properties;
    private final AircraftTypePreferenceCatalog preferences;
    private final Clock clock;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = Snapshot.expired();

    @Autowired
    public AtfmAircraftTypeResolver(AtfmDatabaseProperties properties,
                                    AircraftTypePreferenceCatalog preferences) {
        this(properties, preferences, Clock.systemUTC());
    }

    public AtfmAircraftTypeResolver(AtfmDatabaseProperties properties) {
        this(properties, new AircraftTypePreferenceCatalog(), Clock.systemUTC());
    }

    AtfmAircraftTypeResolver(AtfmDatabaseProperties properties, Clock clock) {
        this(properties, new AircraftTypePreferenceCatalog(), clock);
    }

    AtfmAircraftTypeResolver(AtfmDatabaseProperties properties,
                             AircraftTypePreferenceCatalog preferences,
                             Clock clock) {
        this.properties = properties;
        this.preferences = preferences;
        this.clock = clock;
    }

    public ResolvedAircraft resolve(List<String> candidateCodes) {
        List<String> candidates = candidateCodes == null
                ? List.of()
                : candidateCodes.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(candidate -> !candidate.isBlank())
                        .toList();
        Snapshot current = currentSnapshot();
        for (String candidate : candidates) {
            List<AircraftRecord> matches = current.byCode().getOrDefault(canonical(candidate), List.of());
            if (matches.isEmpty()) {
                continue;
            }
            if (matches.size() > 1) {
                Long preferredCraftId = preferences.preferredCraftId(candidate);
                if (preferredCraftId == null) {
                    throw new AmbiguousAircraftTypeException(candidate, matches);
                }
                AircraftRecord preferred = matches.stream()
                        .filter(match -> match.craftId() == preferredCraftId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Preferred aircraft craftId %d for %s is not present in M_CRAFT_TYPE"
                                        .formatted(preferredCraftId, candidate)));
                return resolved(preferred, candidate);
            }
            return resolved(matches.getFirst(), candidate);
        }
        throw new AircraftTypeNotFoundException(candidates);
    }

    private ResolvedAircraft resolved(AircraftRecord record, String candidate) {
        return new ResolvedAircraft(
                record.craftId(),
                Objects.requireNonNullElse(record.mtow(), BigDecimal.ZERO),
                candidate);
    }

    private Snapshot currentSnapshot() {
        Instant now = clock.instant();
        Snapshot current = snapshot;
        if (now.isBefore(current.expiresAt())) {
            return current;
        }
        synchronized (refreshLock) {
            current = snapshot;
            if (now.isBefore(current.expiresAt())) {
                return current;
            }
            snapshot = loadSnapshot(now);
            return snapshot;
        }
    }

    private Snapshot loadSnapshot(Instant loadedAt) {
        if (properties.getUrl().isBlank() || properties.getUsername().isBlank()) {
            throw new IllegalStateException("ATFM database URL and username must be configured");
        }
        Map<String, LinkedHashMap<Long, AircraftRecord>> indexed = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
             PreparedStatement statement = connection.prepareStatement(LOAD_AIRCRAFT_TYPES_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                AircraftRecord record = new AircraftRecord(
                        rows.getLong("CRAFT_ID"),
                        clean(rows.getString("MA")),
                        clean(rows.getString("SOHIEU")),
                        clean(rows.getString("SOHIEU2")),
                        rows.getBigDecimal("TAITRONG"));
                index(indexed, record.ma(), record);
                index(indexed, record.sohieu(), record);
                index(indexed, record.sohieu2(), record);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to load aircraft reference data from ATFM: " + exception.getMessage(),
                    exception);
        }

        Map<String, List<AircraftRecord>> byCode = new LinkedHashMap<>();
        indexed.forEach((code, records) -> byCode.put(code, List.copyOf(records.values())));
        long ttlSeconds = Math.max(1, properties.getAircraftCacheTtlSeconds());
        return new Snapshot(Map.copyOf(byCode), loadedAt.plusSeconds(ttlSeconds));
    }

    private void index(Map<String, LinkedHashMap<Long, AircraftRecord>> indexed,
                       String value,
                       AircraftRecord record) {
        String code = canonical(value);
        if (!code.isBlank()) {
            indexed.computeIfAbsent(code, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(record.craftId(), record);
        }
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String canonical(String value) {
        String folded = Normalizer.normalize(
                        value == null ? "" : value,
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public record ResolvedAircraft(long craftId, BigDecimal mtow, String matchedCode) {
    }

    public static final class AircraftTypeNotFoundException extends RuntimeException {
        private final List<String> candidates;

        private AircraftTypeNotFoundException(List<String> candidates) {
            super("ATFM aircraft type not found for candidates: "
                    + (candidates.isEmpty() ? "<empty>" : String.join(", ", candidates)));
            this.candidates = List.copyOf(candidates);
        }

        public List<String> getCandidates() {
            return candidates;
        }
    }

    public static final class AmbiguousAircraftTypeException extends RuntimeException {
        private final String candidate;
        private final List<Long> craftIds;

        private AmbiguousAircraftTypeException(String candidate, List<AircraftRecord> matches) {
            super("Ambiguous ATFM aircraft type %s; matching craft IDs: %s".formatted(
                    candidate,
                    matches.stream()
                            .map(match -> Long.toString(match.craftId()))
                            .toList()));
            this.candidate = candidate;
            this.craftIds = matches.stream().map(AircraftRecord::craftId).toList();
        }

        public String getCandidate() {
            return candidate;
        }

        public List<Long> getCraftIds() {
            return craftIds;
        }
    }

    private record AircraftRecord(
            long craftId,
            String ma,
            String sohieu,
            String sohieu2,
            BigDecimal mtow
    ) {
    }

    private record Snapshot(Map<String, List<AircraftRecord>> byCode, Instant expiresAt) {
        private static Snapshot expired() {
            return new Snapshot(Map.of(), Instant.EPOCH);
        }
    }
}
