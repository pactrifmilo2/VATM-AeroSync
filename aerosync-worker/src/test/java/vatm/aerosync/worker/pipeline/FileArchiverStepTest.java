package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.enums.FileSourceType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class FileArchiverStepTest {

    @TempDir
    Path tempDir;

    private FileArchiverStep fileArchiverStep;
    private Path processedDir;
    private Path errorDir;
    private Path quarantineDir;

    @BeforeEach
    void setUp() {
        processedDir = tempDir.resolve("processed");
        errorDir = tempDir.resolve("error");
        quarantineDir = tempDir.resolve("quarantine");

        FilePathProperties paths = new FilePathProperties();
        paths.setProcessed(processedDir.toString());
        paths.setError(errorDir.toString());
        paths.setQuarantine(quarantineDir.toString());

        Clock fixed = Clock.fixed(Instant.parse("2026-06-04T10:15:30Z"), ZoneId.of("UTC"));
        fileArchiverStep = new FileArchiverStep(paths, fixed);
    }

    @Test
    void archiveProcessed_usesSenderBasedNamingForFilesystem() throws Exception {
        Path source = tempDir.resolve("incoming").resolve("flight.csv");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "data");

        Path archived = fileArchiverStep.archiveProcessed(source, FileSourceType.FILESYSTEM, null);

        assertThat(Files.exists(archived)).isTrue();
        assertThat(archived.getParent()).isEqualTo(processedDir.resolve("2026").resolve("06").resolve("04"));
        // No sender => "local" prefix
        assertThat(archived.getFileName().toString())
                .startsWith("local_20260604_101530_fs_flight.csv");
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    void archiveProcessed_usesSenderPrefixForEmail() throws Exception {
        Path source = tempDir.resolve("incoming").resolve("flight.csv");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "data");

        Path archived = fileArchiverStep.archiveProcessed(source, FileSourceType.EMAIL,
                "haibdhe140272@fpt.edu.vn");

        assertThat(Files.exists(archived)).isTrue();
        assertThat(archived.getParent()).isEqualTo(processedDir.resolve("2026").resolve("06").resolve("04"));
        assertThat(archived.getFileName().toString())
                .startsWith("haibdhe140272_20260604_101530_email_flight.csv");
    }

    @Test
    void archiveProcessed_sanitizesSenderWithDots() throws Exception {
        Path source = tempDir.resolve("incoming").resolve("data.csv");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "data");

        Path archived = fileArchiverStep.archiveProcessed(source, FileSourceType.EMAIL,
                "first.last@example.com");

        assertThat(archived.getFileName().toString())
                .startsWith("firstlast_20260604_101530_email_data.csv");
    }

    @Test
    void archiveError_movesFileAndWritesLogWithSender() throws Exception {
        Path source = tempDir.resolve("bad.csv");
        Files.writeString(source, "bad");

        Path archived = fileArchiverStep.archiveError(source, FileSourceType.EMAIL,
                "Invalid encoding", "ops@vatm.local");

        assertThat(Files.exists(archived)).isTrue();
        assertThat(archived.getParent()).isEqualTo(errorDir.resolve("2026").resolve("06").resolve("04"));
        assertThat(archived.getFileName().toString()).startsWith("ops_");
        assertThat(archived.getFileName().toString()).contains("_email_");
        Path log = archived.resolveSibling(archived.getFileName() + ".log");
        assertThat(log.getParent()).isEqualTo(archived.getParent());
        assertThat(Files.readString(log)).contains("Invalid encoding");
    }

    @Test
    void archiveQuarantine_movesToQuarantineDirectoryWithSender() throws Exception {
        Path source = tempDir.resolve("rule.csv");
        Files.writeString(source, "data");

        Path archived = fileArchiverStep.archiveQuarantine(source, FileSourceType.FILESYSTEM, null);

        assertThat(archived.startsWith(quarantineDir)).isTrue();
        assertThat(archived.getParent()).isEqualTo(quarantineDir.resolve("2026").resolve("06").resolve("04"));
        assertThat(archived.getFileName().toString()).contains("quarantine");
        assertThat(archived.getFileName().toString()).startsWith("local_");
    }
}
