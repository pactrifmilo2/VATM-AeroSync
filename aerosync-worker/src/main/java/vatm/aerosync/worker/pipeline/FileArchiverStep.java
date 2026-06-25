package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.enums.FileSourceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class FileArchiverStep {

    static final DateTimeFormatter ARCHIVE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final FilePathProperties filePathProperties;
    private final Clock clock;

    public FileArchiverStep(FilePathProperties filePathProperties, Clock clock) {
        this.filePathProperties = filePathProperties;
        this.clock = clock;
    }

    public Path archiveProcessed(Path sourceFile, FileSourceType sourceType, String sender) throws IOException {
        return moveTo(sourceFile, Path.of(filePathProperties.getProcessed()), sourceType, null, sender);
    }

    public Path archiveError(Path sourceFile, FileSourceType sourceType, String errorDetail, String sender)
            throws IOException {
        Path destination = moveTo(sourceFile, Path.of(filePathProperties.getError()), sourceType, null, sender);
        Path logFile = destination.resolveSibling(destination.getFileName() + ".log");
        Files.writeString(logFile, errorDetail);
        return destination;
    }

    public Path archiveQuarantine(Path sourceFile, FileSourceType sourceType, String sender) throws IOException {
        return moveTo(sourceFile, Path.of(filePathProperties.getQuarantine()), sourceType, "quarantine", sender);
    }

    private Path moveTo(Path sourceFile, Path targetDir, FileSourceType sourceType, String prefix, String sender)
            throws IOException {
        Files.createDirectories(targetDir);
        String sourceLabel = sourceType == FileSourceType.EMAIL ? "email" : "fs";
        String senderLocal = sanitizeSender(sender);
        LocalDateTime now = LocalDateTime.now(clock);
        String date = now.format(ARCHIVE_DATE);
        String time = now.format(ARCHIVE_TIME);
        String originalName = sourceFile.getFileName().toString();
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext = dot > 0 ? originalName.substring(dot) : "";

        StringBuilder name = new StringBuilder(senderLocal)
                .append('_').append(date)
                .append('_').append(time)
                .append('_').append(sourceLabel);
        if (prefix != null) {
            name.append('_').append(prefix);
        }
        name.append('_').append(base).append(ext);

        Path target = targetDir.resolve(name.toString());
        return Files.move(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String sanitizeSender(String sender) {
        if (sender == null || sender.isBlank()) {
            return "local";
        }
        int atIndex = sender.indexOf('@');
        String local = atIndex > 0 ? sender.substring(0, atIndex) : sender;
        return local.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
