package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingSourceDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingSourceSummaryResponse;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingSourceService {

    static final int MAX_PAGE_SIZE = 100;

    private final PermitTrainingSourceRepository sourceRepository;
    private final FilePathProperties filePathProperties;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingSourceService(
            PermitTrainingSourceRepository sourceRepository,
            FilePathProperties filePathProperties) {
        this.sourceRepository = sourceRepository;
        this.filePathProperties = filePathProperties;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PermitTrainingSourceSummaryResponse> list(
            PermitTrainingSourceState state,
            int page,
            int size) {
        validatePage(page, size);
        PageRequest request = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<PermitTrainingSource> sources = state == null
                ? sourceRepository.findAll(request)
                : sourceRepository.findByState(state, request);
        return PagedResponse.from(sources.map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public PermitTrainingSourceDetailResponse get(Long id) {
        return toDetail(find(id));
    }

    @Transactional
    public PermitTrainingSourceDetailResponse retain(Long id) {
        PermitTrainingSource source = sourceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training source not found: " + id));
        Path currentCorpusPath = existingCorpusPath(source);
        if (currentCorpusPath != null && Files.isRegularFile(currentCorpusPath)) {
            return toDetail(source);
        }

        Path sourcePath = Path.of(source.getFileRecord().getStoredPath())
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalStateException(
                    "Source document is no longer available: "
                            + source.getOriginalFileName());
        }
        String extension = wordExtension(source.getOriginalFileName());
        Path corpusRoot = trainingRoot();
        String sourceHash = requireSha256(source.getSourceHash());
        Path destination = corpusRoot
                .resolve(sourceHash.substring(0, 2))
                .resolve(sourceHash + extension)
                .normalize();
        if (!destination.startsWith(corpusRoot)) {
            throw new IllegalStateException("Invalid training corpus destination");
        }
        try {
            Files.createDirectories(destination.getParent());
            try {
                Files.copy(
                        sourcePath,
                        destination,
                        StandardCopyOption.COPY_ATTRIBUTES);
            } catch (FileAlreadyExistsException ignored) {
                // A matching content-addressed source is already retained.
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to retain training source: "
                            + exception.getMessage(),
                    exception);
        }

        source.setCorpusPath(destination.toString());
        source.setRetainedAt(LocalDateTime.now());
        return toDetail(sourceRepository.save(source));
    }

    private PermitTrainingSource find(Long id) {
        return sourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training source not found: " + id));
    }

    private PermitTrainingSourceSummaryResponse toSummary(
            PermitTrainingSource source) {
        FileRecord file = source.getFileRecord();
        return new PermitTrainingSourceSummaryResponse(
                source.getId(),
                file.getId(),
                file.getSyncJob().getId(),
                source.getState(),
                source.getSourceHash(),
                source.getOriginalFileName(),
                source.getProfileId(),
                source.getProfileVersion(),
                source.getConfidence(),
                source.getCorpusPath() != null,
                source.getRetainedAt(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }

    private PermitTrainingSourceDetailResponse toDetail(
            PermitTrainingSource source) {
        FileRecord file = source.getFileRecord();
        return new PermitTrainingSourceDetailResponse(
                source.getId(),
                file.getId(),
                file.getSyncJob().getId(),
                source.getState(),
                source.getSourceHash(),
                source.getOriginalFileName(),
                source.getProfileId(),
                source.getProfileVersion(),
                source.getConfidence(),
                readDocument(source.getDocumentJson()),
                source.getParseError(),
                source.getCorpusPath() != null,
                source.getRetainedAt(),
                source.getVersion(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }

    private PermitTrainingDocument readDocument(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PermitTrainingDocument.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored training document is invalid",
                    exception);
        }
    }

    private Path trainingRoot() {
        String configured = filePathProperties.getTraining();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "app.file-paths.training is not configured");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private Path existingCorpusPath(PermitTrainingSource source) {
        String stored = source.getCorpusPath();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return Path.of(stored).toAbsolutePath().normalize();
    }

    private String requireSha256(String hash) {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(
                    "Training source does not have a valid SHA-256 hash");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private String wordExtension(String fileName) {
        if (fileName == null) {
            throw new IllegalStateException(
                    "Training source is not a Word document");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) {
            return ".docx";
        }
        if (lower.endsWith(".doc")) {
            return ".doc";
        }
        throw new IllegalStateException(
                "Training source is not a Word document");
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
