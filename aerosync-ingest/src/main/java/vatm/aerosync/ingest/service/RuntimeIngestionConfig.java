package vatm.aerosync.ingest.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.ingest.config.IngestProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Reads the source selector saved by the API/UI without restarting the ingest process. */
@Service
public class RuntimeIngestionConfig {

    private final JdbcTemplate jdbcTemplate;
    private final IngestProperties defaults;
    private final Clock clock;
    private final AtomicReference<CachedSettings> cached = new AtomicReference<>();

    private static final long CACHE_MILLIS = 5_000L;

    public RuntimeIngestionConfig(JdbcTemplate jdbcTemplate, IngestProperties defaults) {
        this(jdbcTemplate, defaults, Clock.systemUTC());
    }

    RuntimeIngestionConfig(JdbcTemplate jdbcTemplate, IngestProperties defaults, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaults = defaults;
        this.clock = clock;
    }

    public Settings read() {
        Instant now = clock.instant();
        CachedSettings value = cached.get();
        if (value != null && now.isBefore(value.expiresAt())) {
            return value.settings();
        }
        try {
            Settings settings = jdbcTemplate.query("""
                    SELECT ingestion_mode, folder_polling_interval_ms,
                           scheduler_fixed_delay_ms, max_files_per_cycle,
                           allowlist_initialized
                      FROM runtime_config WHERE id = 1
                    """, rows -> {
                if (!rows.next()) {
                    return fallback();
                }
                boolean allowlistInitialized = rows.getBoolean("allowlist_initialized");
                List<String> allowedSenders = allowlistInitialized
                        ? readSenders("runtime_config_allowlist") : List.of();
                List<String> blacklistSenders = readSenders("runtime_config_blacklist");
                return new Settings(
                        normalizeMode(rows.getString("ingestion_mode")),
                        Math.max(10_000L, rows.getLong("folder_polling_interval_ms")),
                        Math.max(60_000L, rows.getLong("scheduler_fixed_delay_ms")),
                        Math.max(1, rows.getInt("max_files_per_cycle")),
                        normalizedSet(allowedSenders), normalizedSet(blacklistSenders));
            });
            cached.set(new CachedSettings(settings, now.plusMillis(CACHE_MILLIS)));
            return settings;
        } catch (RuntimeException ignored) {
            // The API may still be starting/migrating runtime_config. Keep the
            // normal scheduler defaults, but fail closed for email sender policy.
            return fallback();
        }
    }

    private List<String> readSenders(String table) {
        return jdbcTemplate.query("SELECT sender FROM " + table + " WHERE config_id = 1",
                (rows, rowNum) -> rows.getString(1));
    }

    private Set<String> normalizedSet(List<String> senders) {
        return senders.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean mayReadSender(String sender) {
        if (sender == null || sender.isBlank()) return false;
        Settings settings = read();
        String normalized = sender.trim().toLowerCase(Locale.ROOT);
        return settings.allowedSenders().contains(normalized)
                && !settings.blacklistSenders().contains(normalized);
    }

    private Settings fallback() {
        return new Settings("EMAIL", defaults.getSchedulerFixedDelayMs(),
                defaults.getSchedulerFixedDelayMs(), defaults.getMaxFilesPerCycle(), Set.of(), Set.of());
    }

    private String normalizeMode(String mode) {
        if ("FOLDER".equalsIgnoreCase(mode)) {
            return "FOLDER";
        }
        if ("BOTH".equalsIgnoreCase(mode)) {
            return "BOTH";
        }
        return "EMAIL";
    }

    public record Settings(String mode, long folderIntervalMs,
                           long emailIntervalMs, int maxFilesPerCycle,
                           Set<String> allowedSenders, Set<String> blacklistSenders) {
        public Settings(String mode, long folderIntervalMs, long emailIntervalMs, int maxFilesPerCycle) {
            this(mode, folderIntervalMs, emailIntervalMs, maxFilesPerCycle, Set.of(), Set.of());
        }
    }

    private record CachedSettings(Settings settings, Instant expiresAt) { }
}
