package vatm.aerosync.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.worker.config.WorkerProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.stream.Stream;

@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final FilePathProperties filePathProperties;
    private final WorkerProperties workerProperties;

    public RetentionCleanupJob(FilePathProperties filePathProperties, WorkerProperties workerProperties) {
        this.filePathProperties = filePathProperties;
        this.workerProperties = workerProperties;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanup() {
        deleteOlderThan(Path.of(filePathProperties.getProcessed()), workerProperties.getProcessedRetentionDays());
        int errorDays = workerProperties.getErrorRetentionDays();
        deleteOlderThan(Path.of(filePathProperties.getError()), errorDays);
        deleteOlderThan(Path.of(filePathProperties.getQuarantine()), errorDays);
    }

    void deleteOlderThan(Path directory, int retentionDays) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Instant cutoffInstant = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(entry -> {
                try {
                    if (Files.isDirectory(entry)) {
                        tryDeleteDateDir(entry, cutoffDate);
                    } else if (Files.isRegularFile(entry)) {
                        tryDeleteLegacyFile(entry, cutoffInstant);
                    }
                } catch (IOException e) {
                    log.warn("Failed to process {}: {}", entry, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan {}: {}", directory, e.getMessage());
        }
    }

    private void tryDeleteDateDir(Path dir, LocalDate cutoffDate) throws IOException {
        try {
            LocalDate dirDate = LocalDate.parse(dir.getFileName().toString(), DateTimeFormatter.ISO_LOCAL_DATE);
            if (dirDate.isBefore(cutoffDate)) {
                deleteRecursively(dir);
                log.info("Deleted expired day directory: {}", dir);
            }
        } catch (DateTimeParseException e) {
            log.debug("Skipping non-date directory: {}", dir);
        }
    }

    private void tryDeleteLegacyFile(Path file, Instant cutoff) throws IOException {
        FileTime modified = Files.getLastModifiedTime(file);
        if (modified.toInstant().isBefore(cutoff)) {
            Files.delete(file);
            log.info("Deleted expired file: {}", file);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
        }
    }
}
