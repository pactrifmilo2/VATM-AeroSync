package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.enums.FileType;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class PermitTrainingSourceCaptureService {

    private final PermitTrainingSourceRepository sourceRepository;
    private final FileRecordRepository fileRecordRepository;
    private final WordPermitDocumentReader documentReader;
    private final ObjectMapper objectMapper;

    public PermitTrainingSourceCaptureService(
            PermitTrainingSourceRepository sourceRepository,
            FileRecordRepository fileRecordRepository,
            WordPermitDocumentReader documentReader,
            ObjectMapper objectMapper) {
        this.sourceRepository = sourceRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.documentReader = documentReader;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ProcessingContext context,
                       PermitTrainingSourceState state,
                       String error) {
        if (!isWord(context)) {
            return;
        }
        FileRecord fileRecord = fileRecordRepository
                .findFirstBySyncJobIdOrderByIdAsc(
                        context.getEvent().getSyncJobId())
                .orElse(null);
        if (fileRecord == null || fileRecord.getId() == null) {
            return;
        }

        PermitTrainingSource source = sourceRepository
                .findByFileRecordId(fileRecord.getId())
                .orElseGet(PermitTrainingSource::new);
        if (source.getFileRecord() == null) {
            source.setFileRecord(fileRecord);
            source.setSourceHash(resolveHash(context, fileRecord));
            source.setOriginalFileName(
                    truncate(originalFileName(context, fileRecord), 500));
        }
        if (source.getDocumentJson() == null) {
            captureDocument(source, context.getFilePath());
        }

        source.setState(state);
        source.setParseError(error == null
                ? source.getParseError()
                : truncate(error, 2000));
        WordPermitParseResult result = context.getWordPermitParseResult();
        if (result != null) {
            source.setProfileId(result.profileId());
            source.setProfileVersion(result.profileVersion());
            source.setConfidence(result.confidence());
        }
        sourceRepository.save(source);
    }

    private void captureDocument(PermitTrainingSource source, Path path) {
        try {
            WordPermitDocument document = documentReader.read(path);
            source.setDocumentJson(objectMapper.writeValueAsString(
                    toTrainingDocument(document)));
        } catch (IOException | RuntimeException exception) {
            source.setParseError(truncate(
                    "Could not capture structured Word evidence: "
                            + safeMessage(exception),
                    2000));
        }
    }

    private PermitTrainingDocument toTrainingDocument(
            WordPermitDocument document) {
        List<PermitTrainingDocument.Table> tables = new ArrayList<>();
        for (int tableIndex = 0;
             tableIndex < document.tables().size();
             tableIndex++) {
            List<PermitTrainingDocument.Row> rows = new ArrayList<>();
            List<List<String>> sourceRows =
                    document.tables().get(tableIndex);
            for (int rowIndex = 0;
                 rowIndex < sourceRows.size();
                 rowIndex++) {
                List<PermitTrainingDocument.Cell> cells =
                        new ArrayList<>();
                List<String> sourceCells = sourceRows.get(rowIndex);
                for (int columnIndex = 0;
                     columnIndex < sourceCells.size();
                     columnIndex++) {
                    cells.add(new PermitTrainingDocument.Cell(
                            "table-%d-row-%d-cell-%d".formatted(
                                    tableIndex,
                                    rowIndex,
                                    columnIndex),
                            rowIndex,
                            columnIndex,
                            sourceCells.get(columnIndex)));
                }
                rows.add(new PermitTrainingDocument.Row(
                        rowIndex,
                        cells));
            }
            tables.add(new PermitTrainingDocument.Table(
                    tableIndex,
                    document.tableContexts().get(tableIndex),
                    rows));
        }
        return new PermitTrainingDocument(
                document.paragraphText(),
                document.tableText(),
                document.rawContent(),
                tables,
                document.authoredDate());
    }

    private boolean isWord(ProcessingContext context) {
        if (context.getFileType() == FileType.DOC
                || context.getFileType() == FileType.DOCX) {
            return true;
        }
        String name = context.getOriginalFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".doc") || lower.endsWith(".docx");
    }

    private String resolveHash(
            ProcessingContext context,
            FileRecord fileRecord) {
        for (String candidate : List.of(
                value(context.getEvent().getFileHash()),
                value(fileRecord.getChecksum()))) {
            if (candidate.matches("(?i)^[a-f0-9]{64}$")) {
                return candidate.toLowerCase(java.util.Locale.ROOT);
            }
        }
        Path path = context.getFilePath();
        if (path != null && Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (IOException | NoSuchAlgorithmException ignored) {
                // A deterministic metadata fallback is sufficient for inbox identity.
            }
        }
        return sha256(context.getEvent().getSyncJobId()
                + "|" + context.getOriginalFileName());
    }

    private String originalFileName(
            ProcessingContext context,
            FileRecord fileRecord) {
        String recordedName = fileRecord.getOriginalFileName();
        if (recordedName != null && !recordedName.isBlank()) {
            return recordedName;
        }
        return context.getOriginalFileName();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
