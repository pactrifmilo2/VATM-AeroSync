package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.FileType;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;
import vatm.aerosync.worker.model.ProcessingContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermitTrainingSourceCaptureServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void capturesStableWordCellsForFutureGuidedTraining()
            throws Exception {
        Path documentPath = temporaryDirectory.resolve("permit.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph()
                    .createRun()
                    .setText("2. Schedules (UTC Time)");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Flight number");
            table.getRow(0).getCell(1).setText("Effective from");
            table.getRow(1).getCell(0).setText("QR8364");
            table.getRow(1).getCell(1).setText("04AUG26");
            try (var output = Files.newOutputStream(documentPath)) {
                document.write(output);
            }
        }

        PermitTrainingSourceRepository sourceRepository =
                mock(PermitTrainingSourceRepository.class);
        FileRecordRepository fileRepository =
                mock(FileRecordRepository.class);
        ObjectMapper objectMapper =
                new ObjectMapper().findAndRegisterModules();
        PermitTrainingSourceCaptureService service =
                new PermitTrainingSourceCaptureService(
                        sourceRepository,
                        fileRepository,
                        new WordPermitDocumentReader(),
                        objectMapper);
        FileRecord fileRecord = new FileRecord();
        ReflectionTestUtils.setField(fileRecord, "id", 8L);
        fileRecord.setOriginalFileName("permit.docx");
        fileRecord.setStoredPath(documentPath.toString());
        when(fileRepository.findFirstBySyncJobIdOrderByIdAsc(7L))
                .thenReturn(Optional.of(fileRecord));
        when(sourceRepository.findByFileRecordId(8L))
                .thenReturn(Optional.empty());
        when(sourceRepository.save(any(PermitTrainingSource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(
                        7L,
                        documentPath.toString(),
                        "b".repeat(64),
                        FileSourceType.EMAIL,
                        false));
        context.setFilePath(documentPath);
        context.setFileType(FileType.DOCX);

        service.record(
                context,
                PermitTrainingSourceState.REVIEW_REQUIRED,
                "Operator confirmation required");

        ArgumentCaptor<PermitTrainingSource> saved =
                ArgumentCaptor.forClass(PermitTrainingSource.class);
        verify(sourceRepository).save(saved.capture());
        PermitTrainingSource source = saved.getValue();
        PermitTrainingDocument evidence = objectMapper.readValue(
                source.getDocumentJson(),
                PermitTrainingDocument.class);
        assertThat(source.getState())
                .isEqualTo(PermitTrainingSourceState.REVIEW_REQUIRED);
        assertThat(source.getSourceHash()).isEqualTo("b".repeat(64));
        assertThat(source.getParseError())
                .isEqualTo("Operator confirmation required");
        assertThat(evidence.tables()).hasSize(1);
        assertThat(evidence.tables().getFirst().context())
                .contains("Schedules");
        assertThat(evidence.tables().getFirst().rows()
                .get(1).cells().getFirst().id())
                .isEqualTo("table-0-row-1-cell-0");
        assertThat(evidence.tables().getFirst().rows()
                .get(1).cells().getFirst().value())
                .isEqualTo("QR8364");
    }
}
