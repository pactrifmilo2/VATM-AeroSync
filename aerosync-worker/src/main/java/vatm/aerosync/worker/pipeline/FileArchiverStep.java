package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.enums.FileSourceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class FileArchiverStep {

    static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DAILY_DIR = DateTimeFormatter.ISO_LOCAL_DATE;

    private final FilePathProperties filePathProperties;
    private final Clock clock;

    public FileArchiverStep(FilePathProperties filePathProperties, Clock clock) {
        this.filePathProperties = filePathProperties;
        this.clock = clock;
    }

    public Path archiveProcessed(Path sourceFile, FileSourceType sourceType) throws IOException {
        return moveTo(sourceFile, Path.of(filePathProperties.getProcessed()), sourceType, null);
    }

    public Path archiveError(Path sourceFile, FileSourceType sourceType, String errorDetail) throws IOException {
        Path destination = moveTo(sourceFile, Path.of(filePathProperties.getError()), sourceType, null);
        Path logFile = destination.resolveSibling(destination.getFileName() + ".log");
        Files.writeString(logFile, errorDetail);
        return destination;
    }

    public Path archiveQuarantine(Path sourceFile, FileSourceType sourceType) throws IOException {
        return moveTo(sourceFile, Path.of(filePathProperties.getQuarantine()), sourceType, "quarantine");
    }

    private Path moveTo(Path sourceFile, Path targetDir, FileSourceType sourceType, String prefix)
            throws IOException {
        Path dayDir = targetDir.resolve(LocalDate.now(clock).format(DAILY_DIR));
        Files.createDirectories(dayDir);
        String sourceLabel = sourceType == FileSourceType.EMAIL ? "email" : "fs";
        String timestamp = LocalDateTime.now(clock).format(ARCHIVE_TIMESTAMP);
        String originalName = sourceFile.getFileName().toString();
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext = dot > 0 ? originalName.substring(dot) : "";

        StringBuilder name = new StringBuilder("SLB_").append(timestamp).append('_').append(sourceLabel);
        if (prefix != null) {
            name.append('_').append(prefix);
        }
        name.append('_').append(base).append(ext);

        Path target = dayDir.resolve(name.toString());
        return Files.move(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
