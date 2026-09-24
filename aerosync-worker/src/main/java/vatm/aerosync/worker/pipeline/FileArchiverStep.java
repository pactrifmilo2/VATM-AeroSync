package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.enums.FileSourceType;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class FileArchiverStep {

    static final DateTimeFormatter ARCHIVE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("HHmmss");
    static final DateTimeFormatter ARCHIVE_YEAR = DateTimeFormatter.ofPattern("yyyy");
    static final DateTimeFormatter ARCHIVE_MONTH = DateTimeFormatter.ofPattern("MM");
    static final DateTimeFormatter ARCHIVE_DAY = DateTimeFormatter.ofPattern("dd");

    private final FilePathProperties filePathProperties;
    private final Clock clock;

    public FileArchiverStep(FilePathProperties filePathProperties, Clock clock) {
        this.filePathProperties = filePathProperties;
        this.clock = clock;
    }

    public Path archiveProcessed(Path sourceFile, FileSourceType sourceType, String sender) throws IOException {
        Path processedRoot = Path.of(filePathProperties.getProcessed());
        Path targetRoot = sourceType == FileSourceType.FILESYSTEM
                ? processedRoot.resolve("incoming")
                : processedRoot;
        return moveTo(sourceFile, targetRoot, sourceType, null, sender);
    }

    public Path archiveError(Path sourceFile, FileSourceType sourceType, String errorDetail, String sender)
            throws IOException {
        Path destination = moveTo(sourceFile, Path.of(filePathProperties.getError()), sourceType, null, sender);
        writeErrorLog(destination, errorDetail);
        return destination;
    }

    public Path archiveFormatError(Path sourceFile, FileSourceType sourceType, String errorDetail, String sender)
            throws IOException {
        Path root = isMissingWordYamlFormat(sourceFile, errorDetail)
                ? Path.of(filePathProperties.getErrorFormat())
                : Path.of(filePathProperties.getError());
        Path destination = moveTo(sourceFile, root, sourceType, null, sender);
        writeErrorLog(destination, errorDetail);
        return destination;
    }

    private void writeErrorLog(Path destination, String errorDetail) throws IOException {
        Path logFile = destination.resolveSibling(destination.getFileName() + ".log");
        Files.writeString(logFile, errorDetail == null ? "" : errorDetail);
    }

    private boolean isMissingWordYamlFormat(Path sourceFile, String errorDetail) {
        String name = sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if ((!name.endsWith(".doc") && !name.endsWith(".docx")) || errorDetail == null) {
            return false;
        }
        String detail = errorDetail.toLowerCase(Locale.ROOT);
        return detail.contains("unsupported word permit format")
                || detail.contains("no format profile matched")
                || detail.contains("file word ch\u01b0a c\u00f3 format yaml ph\u00f9 h\u1ee3p");
    }

    public Path archiveQuarantine(Path sourceFile, FileSourceType sourceType, String sender) throws IOException {
        return moveTo(sourceFile, Path.of(filePathProperties.getQuarantine()), sourceType, "quarantine", sender);
    }

    private Path moveTo(Path sourceFile, Path targetDir, FileSourceType sourceType, String prefix, String sender)
            throws IOException {
        String sourceLabel = sourceType == FileSourceType.EMAIL ? "email" : "fs";
        String senderLocal = sanitizeSender(sender);
        LocalDateTime now = LocalDateTime.now(clock);
        Path datedTargetDir = targetDir
                .resolve(now.format(ARCHIVE_YEAR))
                .resolve(now.format(ARCHIVE_MONTH))
                .resolve(now.format(ARCHIVE_DAY));
        Files.createDirectories(datedTargetDir);
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
        // Include a short content hash to distinguish files arriving in the same
        // second (for example, two attachments with the same sender/name).
        // Never replace an existing archive; resolve any residual collision with
        // a monotonic suffix so each archived file remains recoverable.
        name.append('_').append(base).append('_').append(shortFileHash(sourceFile));
        Path target = datedTargetDir.resolve(name + ext);
        int collision = 0;
        while (true) {
            Path candidate = collision == 0
                    ? target
                    : datedTargetDir.resolve(name + "_" + collision + ext);
            try {
                return Files.move(sourceFile, candidate);
            } catch (FileAlreadyExistsException ex) {
                collision++;
            }
        }
    }

    private String shortFileHash(Path sourceFile) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sourceFile));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException ex) {
            // Hashing is an enhancement; retain archiving if a filesystem read
            // unexpectedly fails and let the collision suffix guarantee safety.
            return "unhashed";
        }
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
