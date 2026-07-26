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
    void deleteOlderThan_removesExpiredFilesRecursivelyAndEmptyDateFolders() throws Exception {
        Path processed = tempDir.resolve("processed");
        Path oldDay = processed.resolve("2025").resolve("01").resolve("01");
        Files.createDirectories(oldDay);
        Path oldFile = oldDay.resolve("old.csv");
        Files.writeString(oldFile, "x");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(70, ChronoUnit.DAYS)));

        Path recentDay = processed.resolve("2026").resolve("07").resolve("18");
        Files.createDirectories(recentDay);
        Path recentFile = recentDay.resolve("recent.csv");
        Files.writeString(recentFile, "y");

        job.deleteOlderThan(processed, 60);

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(oldDay)).isFalse();
        assertThat(Files.exists(processed.resolve("2025"))).isFalse();
        assertThat(Files.exists(recentFile)).isTrue();
        assertThat(Files.exists(recentDay)).isTrue();
        assertThat(Files.exists(processed)).isTrue();
    }

    @Test
    void deleteOlderThan_stillSupportsLegacyFilesAtArchiveRoot() throws Exception {
        Path error = tempDir.resolve("error");
        Files.createDirectories(error);
        Path oldFile = error.resolve("legacy.csv");
        Files.writeString(oldFile, "x");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(100, ChronoUnit.DAYS)));

        job.deleteOlderThan(error, 90);

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(error)).isTrue();
    }
}
