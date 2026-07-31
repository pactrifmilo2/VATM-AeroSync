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
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.enums.SyncStatus;
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
    @Mock
    private PermitTrainingSourceCaptureService trainingSourceCaptureService;

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
                aircraftTypeResolutionStep,
                viaResolutionStep,
                businessRuleValidatorStep,
                databaseWriterStep,
                fileArchiverStep,
                auditLogService,
                syncResultPublisher,
                trainingSourceCaptureService);

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
    void process_tracksDatabaseAndArchiveSuccessSeparately() throws Exception {
        Path archived = Path.of("C:/archive/processed.csv");
        when(fileArchiverStep.archiveProcessed(any(), any(), any())).thenReturn(archived);

        pipeline.process(event);

        InOrder processingOrder = inOrder(
                normalizerStep,
                aircraftTypeResolutionStep,
                viaResolutionStep,
                businessRuleValidatorStep,
                databaseWriterStep);
        processingOrder.verify(normalizerStep).normalize(any());
        processingOrder.verify(aircraftTypeResolutionStep).resolve(any());
        processingOrder.verify(viaResolutionStep).resolve(any());
        processingOrder.verify(businessRuleValidatorStep).validate(any());
        processingOrder.verify(databaseWriterStep).write(any());
        verify(trainingSourceCaptureService).record(
                any(),
                org.mockito.ArgumentMatchers.eq(
                        PermitTrainingSourceState.PROCESSING),
                org.mockito.ArgumentMatchers.isNull());
        verify(trainingSourceCaptureService).record(
                any(),
                org.mockito.ArgumentMatchers.eq(
                        PermitTrainingSourceState.PARSED),
                org.mockito.ArgumentMatchers.isNull());

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
        assertThat(record.getErrorMessage()).contains("archive unavailable");
    }

    @Test
    void process_quarantinesPermanentAtfmReferenceFailureWithoutRethrowing() throws Exception {
        SyncJob job = new SyncJob();
        job.setFileHash("a".repeat(64));
        when(syncJobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(fileArchiverStep.archiveQuarantine(any(), any(), any()))
                .thenReturn(Path.of("C:/archive/quarantine.csv"));
        doThrow(new AtfmReferenceDataException("ATFM lookup not found: M_OPER.OPER_ICAO=POS"))
                .when(databaseWriterStep).write(any());

        pipeline.process(event);

        assertThat(job.getStatus()).isEqualTo(SyncStatus.QUARANTINED);
        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.QUARANTINED);
        assertThat(record.getErrorMessage()).contains("M_OPER.OPER_ICAO=POS");
        verify(syncResultPublisher).publish(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(SyncStatus.QUARANTINED),
                any(),
                org.mockito.ArgumentMatchers.contains("BR-ATFM-REFERENCE"));
        verify(trainingSourceCaptureService).record(
                any(),
                org.mockito.ArgumentMatchers.eq(
                        PermitTrainingSourceState.QUARANTINED),
                org.mockito.ArgumentMatchers.contains(
                        "M_OPER.OPER_ICAO=POS"));
    }
}
