package vatm.aerosync.worker.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.worker.config.WorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionCleanupJobTest {

    @TempDir
    Path tempDir;

    private RetentionCleanupJob job;

    @BeforeEach
    void setUp() {
        FilePathProperties paths = new FilePathProperties();
        paths.setProcessed(tempDir.resolve("processed").toString());
        paths.setError(tempDir.resolve("error").toString());
        paths.setQuarantine(tempDir.resolve("quarantine").toString());

        WorkerProperties workerProperties = new WorkerProperties();
        workerProperties.setProcessedRetentionDays(60);
        workerProperties.setErrorRetentionDays(90);

        job = new RetentionCleanupJob(paths, workerProperties);
    }

    @Test
    void deleteOlderThan_removesExpiredLegacyFiles() throws Exception {
        Path processed = tempDir.resolve("processed");
        Files.createDirectories(processed);
        Path oldFile = processed.resolve("old.csv");
        Files.writeString(oldFile, "x");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(70, ChronoUnit.DAYS)));

        Path recentFile = processed.resolve("recent.csv");
        Files.writeString(recentFile, "y");

        job.deleteOlderThan(processed, 60);

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(recentFile)).isTrue();
    }

    @Test
    void deleteOlderThan_removesExpiredDayDirectories() throws Exception {
        Path processed = tempDir.resolve("processed");
        Files.createDirectories(processed);

        // Create an old day directory
        Path oldDay = processed.resolve("2026-04-01");
        Files.createDirectories(oldDay);
        Files.writeString(oldDay.resolve("flight.csv"), "old-data");

        // Create a recent day directory
        Path recentDay = processed.resolve("2026-07-06");
        Files.createDirectories(recentDay);
        Files.writeString(recentDay.resolve("flight.csv"), "recent-data");

        job.deleteOlderThan(processed, 60);

        // Old day dir (April 2026, ~90 days ago from July 2026) should be gone
        assertThat(Files.exists(oldDay)).isFalse();
        // Recent day dir should remain
        assertThat(Files.exists(recentDay)).isTrue();
    }

    @Test
    void deleteOlderThan_ignoresNonDateDirectories() throws Exception {
        Path processed = tempDir.resolve("processed");
        Files.createDirectories(processed);

        Path nonDateDir = processed.resolve("some-custom-folder");
        Files.createDirectories(nonDateDir);
        Files.writeString(nonDateDir.resolve("data.csv"), "data");

        job.deleteOlderThan(processed, 1);

        // Non-date directory should be left alone
        assertThat(Files.exists(nonDateDir)).isTrue();
    }
}
