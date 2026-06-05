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
import java.time.temporal.ChronoUnit;
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
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    FileTime modified = Files.getLastModifiedTime(path);
                    if (modified.toInstant().isBefore(cutoff)) {
                        Files.delete(path);
                        log.info("Deleted expired file: {}", path);
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan {}: {}", directory, e.getMessage());
        }
    }
}
