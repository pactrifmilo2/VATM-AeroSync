package vatm.aerosync.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermitTrainingSourceServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainCopiesSourceToContentAddressedCorpus() throws Exception {
        PermitTrainingSourceRepository repository =
                mock(PermitTrainingSourceRepository.class);
        FilePathProperties paths = new FilePathProperties();
        Path trainingRoot = temporaryDirectory.resolve("training");
        paths.setTraining(trainingRoot.toString());
        ObjectMapper objectMapper =
                new ObjectMapper().findAndRegisterModules();
        PermitTrainingSourceService service =
                new PermitTrainingSourceService(
                        repository,
                        paths);

        Path original = temporaryDirectory.resolve("source.docx");
        Files.writeString(original, "word-content");
        PermitTrainingSource source = source(original, objectMapper);
        when(repository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(source));
        when(repository.save(any(PermitTrainingSource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.retain(11L);

        Path retained = Path.of(source.getCorpusPath());
        assertThat(response.retained()).isTrue();
        assertThat(response.document().tables()).hasSize(1);
        assertThat(retained)
                .startsWith(trainingRoot.toAbsolutePath().normalize());
        assertThat(retained.getFileName().toString())
                .isEqualTo("a".repeat(64) + ".docx");
        assertThat(Files.readString(retained)).isEqualTo("word-content");
    }

    private PermitTrainingSource source(
            Path original,
            ObjectMapper objectMapper) throws Exception {
        SyncJob job = new SyncJob();
        ReflectionTestUtils.setField(job, "id", 7L);
        FileRecord file = new FileRecord();
        ReflectionTestUtils.setField(file, "id", 9L);
        file.setSyncJob(job);
        file.setOriginalFileName("source.docx");
        file.setStoredPath(original.toString());

        PermitTrainingSource source = new PermitTrainingSource();
        ReflectionTestUtils.setField(source, "id", 11L);
        source.setFileRecord(file);
        source.setState(PermitTrainingSourceState.REVIEW_REQUIRED);
        source.setSourceHash("a".repeat(64));
        source.setOriginalFileName("source.docx");
        source.setDocumentJson(objectMapper.writeValueAsString(
                new PermitTrainingDocument(
                        "Permit",
                        "Flight number",
                        "Permit\nFlight number",
                        List.of(new PermitTrainingDocument.Table(
                                0,
                                "Schedules",
                                List.of())),
                        null)));
        return source;
    }
}
