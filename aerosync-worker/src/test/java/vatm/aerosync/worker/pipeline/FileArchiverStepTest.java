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
    void archiveProcessed_usesSlbNamingConvention() throws Exception {
        Path source = tempDir.resolve("incoming").resolve("flight.csv");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "data");

        Path archived = fileArchiverStep.archiveProcessed(source, FileSourceType.FILESYSTEM);

        assertThat(Files.exists(archived)).isTrue();
        assertThat(archived.getFileName().toString())
                .startsWith("SLB_20260604_101530_fs_flight.csv");
        assertThat(archived.getParent().getFileName().toString()).isEqualTo("2026-06-04");
        assertThat(archived.getParent().getParent()).isEqualTo(processedDir);
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    void archiveError_movesFileAndWritesLog() throws Exception {
        Path source = tempDir.resolve("bad.csv");
        Files.writeString(source, "bad");

        Path archived = fileArchiverStep.archiveError(source, FileSourceType.EMAIL, "Invalid encoding");

        assertThat(Files.exists(archived)).isTrue();
        assertThat(archived.getFileName().toString()).contains("_email_");
        assertThat(archived.getParent().getFileName().toString()).isEqualTo("2026-06-04");
        assertThat(archived.getParent().getParent()).isEqualTo(errorDir);
        Path log = archived.resolveSibling(archived.getFileName() + ".log");
        assertThat(Files.readString(log)).contains("Invalid encoding");
    }

    @Test
    void archiveQuarantine_movesToQuarantineDirectory() throws Exception {
        Path source = tempDir.resolve("rule.csv");
        Files.writeString(source, "data");

        Path archived = fileArchiverStep.archiveQuarantine(source, FileSourceType.FILESYSTEM);

        assertThat(archived.getParent().getFileName().toString()).isEqualTo("2026-06-04");
        assertThat(archived.getParent().getParent()).isEqualTo(quarantineDir);
        assertThat(archived.getFileName().toString()).contains("quarantine");
    }
}
