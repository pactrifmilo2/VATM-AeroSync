package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.service.AuditLogService;
import vatm.aerosync.worker.service.SyncResultPublisher;
import vatm.aerosync.worker.atfm.AtfmReferenceDataException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileProcessingPipelineTest {

    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private EmailMetadataRepository emailMetadataRepository;
    @Mock
    private FileRecordRepository fileRecordRepository;
    @Mock
    private FormatValidatorStep formatValidatorStep;
    @Mock
    private ParserStep parserStep;
    @Mock
    private NormalizerStep normalizerStep;
    @Mock
    private PurposeResolutionStep purposeResolutionStep;
    @Mock
    private RevisionReconciliationStep revisionReconciliationStep;
    @Mock
    private AircraftTypeResolutionStep aircraftTypeResolutionStep;
    @Mock
    private ViaResolutionStep viaResolutionStep;
    @Mock
    private BusinessRuleValidatorStep businessRuleValidatorStep;
    @Mock
    private DatabaseWriterStep databaseWriterStep;
    @Mock
    private FileArchiverStep fileArchiverStep;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private SyncResultPublisher syncResultPublisher;

    private FileProcessingPipeline pipeline;
    private FileRecord record;
    private FileIngestedEvent event;

    @BeforeEach
    void setUp() {
        pipeline = new FileProcessingPipeline(
                syncJobRepository,
                emailMetadataRepository,
                fileRecordRepository,
                formatValidatorStep,
                parserStep,
                normalizerStep,
                purposeResolutionStep,
                revisionReconciliationStep,
                aircraftTypeResolutionStep,
                viaResolutionStep,
                businessRuleValidatorStep,
                databaseWriterStep,
                fileArchiverStep,
                auditLogService,
                syncResultPublisher);

        record = new FileRecord();
        record.setSourceType(FileSourceType.EMAIL);
        record.setOriginalFileName("flights.csv");
        record.setStoredPath("C:/staging/flights.csv");
        record.setProcessingStatus(FileProcessingStatus.DOWNLOADED);
        record.setArchiveStatus(FileArchiveStatus.PENDING);

        event = new FileIngestedEvent(
                7L,
                "C:/staging/flights.csv",
                "a".repeat(64),
                FileSourceType.EMAIL,
                false);

        when(emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(7L)).thenReturn(Optional.empty());
        when(syncJobRepository.findById(7L)).thenReturn(Optional.empty());
        when(fileRecordRepository.findBySyncJobId(7L)).thenReturn(List.of(record));
        when(fileRecordRepository.save(any(FileRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(databaseWriterStep.write(any())).thenAnswer(invocation -> {
            record.setProcessingStatus(FileProcessingStatus.SAVED);
            record.setRowsSaved(0);
            record.setDatabaseSavedAt(LocalDateTime.now());
            return DatabaseWriteResult.success(0);
        });
    }

    @Test
    void receivedDateUsesEmailAndRemainsStableOnRetry() throws Exception {
        var metadata = new vatm.aerosync.common.entity.EmailMetadata();
        metadata.setReceivedAt(LocalDateTime.of(2026, 9, 23, 23, 55));
        when(emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(7L)).thenReturn(Optional.of(metadata));
        org.springframework.test.util.ReflectionTestUtils.setField(record, "createdAt", LocalDateTime.of(2026, 9, 24, 0, 5));
        when(fileArchiverStep.archiveProcessed(any(), any(), any())).thenReturn(Path.of("C:/archive/processed.csv"));
        java.util.List<java.time.LocalDate> dates = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            vatm.aerosync.worker.model.ProcessingContext context = invocation.getArgument(0);
            dates.add(context.getReceivedDate()); return null;
        }).when(parserStep).parse(any());
        pipeline.process(event);
        pipeline.process(event);
        assertThat(dates).containsExactly(java.time.LocalDate.of(2026, 9, 23), java.time.LocalDate.of(2026, 9, 23));
    }

    @Test
    void receivedDateFallsBackToEarliestIngestRecordNotToday() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(record, "createdAt", LocalDateTime.of(2026, 9, 24, 0, 5));
        var earlier = new FileRecord(); org.springframework.test.util.ReflectionTestUtils.setField(earlier, "createdAt", LocalDateTime.of(2026, 9, 22, 23, 50));
        when(fileRecordRepository.findBySyncJobId(7L)).thenReturn(List.of(record, earlier));
        when(fileArchiverStep.archiveProcessed(any(), any(), any())).thenReturn(Path.of("C:/archive/processed.csv"));
        var captor = org.mockito.ArgumentCaptor.forClass(vatm.aerosync.worker.model.ProcessingContext.class);
        pipeline.process(event);
        verify(parserStep).parse(captor.capture());
        assertThat(captor.getValue().getReceivedDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 22));
    }

    @Test
    void process_tracksDatabaseAndArchiveSuccessSeparately() throws Exception {
        Path archived = Path.of("C:/archive/processed.csv");
        when(fileArchiverStep.archiveProcessed(any(), any(), any())).thenReturn(archived);

        pipeline.process(event);

        InOrder processingOrder = inOrder(
                normalizerStep,
                revisionReconciliationStep,
                aircraftTypeResolutionStep,
                viaResolutionStep,
                businessRuleValidatorStep,
                databaseWriterStep);
        processingOrder.verify(normalizerStep).normalize(any());
        processingOrder.verify(revisionReconciliationStep).reconcile(any());
        processingOrder.verify(aircraftTypeResolutionStep).resolveAllowingMissingForNonSeasonal(any());
        processingOrder.verify(viaResolutionStep).resolveAllowingMissingForNonSeasonal(any());
        processingOrder.verify(businessRuleValidatorStep).validate(any());
        processingOrder.verify(databaseWriterStep).write(any());

        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.SAVED);
        assertThat(record.getRowsSaved()).isZero();
        assertThat(record.getDatabaseSavedAt()).isNotNull();
        assertThat(record.getArchiveStatus()).isEqualTo(FileArchiveStatus.ARCHIVED);
        assertThat(record.getArchivedAt()).isNotNull();
        assertThat(record.getStoredPath()).isEqualTo(archived.toString());
    }

    @Test
    void process_preservesDatabaseSuccessWhenArchiveFails() throws Exception {
        when(fileArchiverStep.archiveProcessed(any(), any(), any()))
                .thenThrow(new IOException("archive unavailable"));

        pipeline.process(event);

        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.SAVED);
        assertThat(record.getDatabaseSavedAt()).isNotNull();
        assertThat(record.getArchiveStatus()).isEqualTo(FileArchiveStatus.FAILED);
        assertThat(record.getArchivedAt()).isNull();
        assertThat(record.getErrorMessage())
                .isEqualTo("Đã xử lý dữ liệu nhưng không thể chuyển file vào thư mục lưu trữ.");
    }

    @Test
    void process_archivesSkippedDatabaseWriteInProcessedDirectory() throws Exception {
        Path archived = Path.of("C:/archive/processed/duplicate.csv");
        when(databaseWriterStep.write(any())).thenReturn(new DatabaseWriteResult(
                SyncStatus.SKIPPED, 1, "Permit already exists; target write skipped"));
        when(fileArchiverStep.archiveProcessed(any(), any(), any())).thenReturn(archived);

        pipeline.process(event);

        verify(fileArchiverStep).archiveProcessed(any(), any(), any());
        assertThat(record.getStoredPath()).isEqualTo(archived.toString());
        assertThat(record.getArchiveStatus()).isEqualTo(FileArchiveStatus.ARCHIVED);
    }

    @Test
    void processArchivesNonPermitAttachmentAsSkipped() throws Exception {
        SyncJob job = new SyncJob();
        job.setFileHash("a".repeat(64));
        Path archived = Path.of("C:/archive/processed/application.docx");
        when(syncJobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(fileArchiverStep.archiveProcessed(any(), any(), any())).thenReturn(archived);
        doThrow(new NonPermitDocumentException("Tài liệu đề nghị, không phải phép bay đã cấp"))
                .when(parserStep).parse(any());

        pipeline.process(event);

        assertThat(job.getStatus()).isEqualTo(SyncStatus.SKIPPED);
        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.SKIPPED);
        assertThat(record.getStoredPath()).isEqualTo(archived.toString());
        verify(fileArchiverStep).archiveProcessed(any(), any(), any());
    }

    @Test
    void processArchivesMissingWordYamlFormatInDedicatedDirectory() throws Exception {
        event = new FileIngestedEvent(
                7L,
                "C:/staging/permit.docx",
                "a".repeat(64),
                FileSourceType.EMAIL,
                false);
        record.setOriginalFileName("permit.docx");
        record.setStoredPath("C:/staging/permit.docx");
        Path archived = Path.of("C:/archive/error-format/permit.docx");
        doThrow(new FormatValidationException(
                "permit.docx", "Unsupported Word permit format; no format profile matched"))
                .when(parserStep).parse(any());
        when(fileArchiverStep.archiveFormatError(any(), any(), any(), any())).thenReturn(archived);

        pipeline.process(event);

        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.FAILED);
        assertThat(record.getStoredPath()).isEqualTo(archived.toString());
        verify(fileArchiverStep).archiveFormatError(any(), any(), any(), any());
    }

    @Test
    void process_quarantinesPermanentAtfmReferenceFailureWithoutRethrowing() throws Exception {
        SyncJob job = new SyncJob();
        job.setFileHash("a".repeat(64));
        when(syncJobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(fileArchiverStep.archiveQuarantine(any(), any(), any()))
                .thenReturn(Path.of("C:/archive/quarantine/reference.csv"));
        doThrow(new AtfmReferenceDataException("ATFM lookup not found: M_OPER.OPER_ICAO=POS"))
                .when(databaseWriterStep).write(any());

        pipeline.process(event);

        assertThat(job.getStatus()).isEqualTo(SyncStatus.QUARANTINED);
        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.QUARANTINED);
        assertThat(record.getErrorMessage())
                .isEqualTo("Không xác định được mã ICAO của hãng khai thác.");
        assertThat(record.getStoredPath()).isEqualTo("C:\\archive\\quarantine\\reference.csv");
        verify(syncResultPublisher).publish(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(SyncStatus.QUARANTINED),
                any(),
                org.mockito.ArgumentMatchers.contains("BR-ATFM-REFERENCE"));
    }
}
