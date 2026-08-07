package vatm.aerosync.ingest.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.ingest.config.IngestProperties;

/** Reads the source selector saved by the API/UI without restarting the ingest process. */
@Service
public class RuntimeIngestionConfig {

    private final JdbcTemplate jdbcTemplate;
    private final IngestProperties defaults;

    public RuntimeIngestionConfig(JdbcTemplate jdbcTemplate, IngestProperties defaults) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaults = defaults;
    }

    public Settings read() {
        try {
            return jdbcTemplate.query("""
                    SELECT ingestion_mode, folder_polling_interval_ms,
                           scheduler_fixed_delay_ms, max_files_per_cycle
                      FROM runtime_config WHERE id = 1
                    """, rows -> {
                if (!rows.next()) {
                    return fallback();
                }
                return new Settings(
                        normalizeMode(rows.getString("ingestion_mode")),
                        Math.max(10_000L, rows.getLong("folder_polling_interval_ms")),
                        Math.max(60_000L, rows.getLong("scheduler_fixed_delay_ms")),
                        Math.max(1, rows.getInt("max_files_per_cycle")));
            });
        } catch (RuntimeException ignored) {
            // The API may still be starting/migrating runtime_config. Keep the
            // immutable application defaults for this cycle and try again later.
            return fallback();
        }
    }

    private Settings fallback() {
        return new Settings("EMAIL", defaults.getSchedulerFixedDelayMs(),
                defaults.getSchedulerFixedDelayMs(), defaults.getMaxFilesPerCycle());
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
                           long emailIntervalMs, int maxFilesPerCycle) {
    }
}
