package vatm.aerosync.ingest.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.ingest.config.IngestProperties;
import vatm.aerosync.ingest.service.EmailIngestService;
import vatm.aerosync.ingest.service.EmailAcknowledgementService;
import vatm.aerosync.ingest.service.FileSystemIngestService;
import vatm.aerosync.ingest.service.RuntimeIngestionConfig;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestSchedulerTest {

    @Mock
    private FileSystemIngestService fileSystemIngestService;

    @Mock
    private EmailIngestService emailIngestService;

    @Mock
    private EmailAcknowledgementService emailAcknowledgementService;

    private IngestScheduler ingestScheduler;

    @BeforeEach
    void setUp() {
        IngestProperties ingestProperties = new IngestProperties();
        ingestProperties.setMaxFilesPerCycle(100);
        ingestScheduler = new IngestScheduler(
                ingestProperties, fileSystemIngestService, emailIngestService, emailAcknowledgementService);
    }

    @Test
    void runCycle_triggersBothScannersWithSharedBudget() {
        when(fileSystemIngestService.ingestUpTo(100)).thenReturn(40);
        when(emailIngestService.ingestUpTo(60)).thenReturn(10);

        ingestScheduler.runCycle();

        verify(emailAcknowledgementService).retryPendingAcknowledgements();
        InOrder order = inOrder(fileSystemIngestService, emailIngestService);
        order.verify(fileSystemIngestService).ingestUpTo(100);
        order.verify(emailIngestService).ingestUpTo(60);
    }

    @Test
    void runCycle_doesNotCallEmailWhenFilesystemUsesFullBudget() {
        when(fileSystemIngestService.ingestUpTo(100)).thenReturn(100);

        ingestScheduler.runCycle();

        verify(fileSystemIngestService).ingestUpTo(100);
        verify(emailIngestService).ingestUpTo(0);
    }

    @Test
    void runCycle_folderModeRunsOnlyFolderScanner() {
        RuntimeIngestionConfig runtime = mock(RuntimeIngestionConfig.class);
        when(runtime.read()).thenReturn(new RuntimeIngestionConfig.Settings(
                "FOLDER", 10_000L, 60_000L, 25));
        IngestScheduler scheduler = new IngestScheduler(
                new IngestProperties(), fileSystemIngestService, emailIngestService,
                emailAcknowledgementService, runtime);

        scheduler.runCycle();

        verify(fileSystemIngestService).ingestUpTo(25);
        verify(emailIngestService, never()).ingestUpTo(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void runCycle_emailModeRunsOnlyEmailScanner() {
        RuntimeIngestionConfig runtime = mock(RuntimeIngestionConfig.class);
        when(runtime.read()).thenReturn(new RuntimeIngestionConfig.Settings(
                "EMAIL", 10_000L, 60_000L, 25));
        IngestScheduler scheduler = new IngestScheduler(
                new IngestProperties(), fileSystemIngestService, emailIngestService,
                emailAcknowledgementService, runtime);

        scheduler.runCycle();

        verify(emailIngestService).ingestUpTo(25);
        verify(fileSystemIngestService, never()).ingestUpTo(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void runCycle_bothModeRunsBothScannersWithIndependentBudgets() {
        RuntimeIngestionConfig runtime = mock(RuntimeIngestionConfig.class);
        when(runtime.read()).thenReturn(new RuntimeIngestionConfig.Settings(
                "BOTH", 10_000L, 60_000L, 25));
        IngestScheduler scheduler = new IngestScheduler(
                new IngestProperties(), fileSystemIngestService, emailIngestService,
                emailAcknowledgementService, runtime);

        scheduler.runCycle();

        verify(fileSystemIngestService).ingestUpTo(25);
        verify(emailIngestService).ingestUpTo(25);
    }
}
