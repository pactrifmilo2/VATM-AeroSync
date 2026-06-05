package vatm.aerosync.ingest.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.ingest.config.IngestProperties;
import vatm.aerosync.ingest.service.EmailIngestService;
import vatm.aerosync.ingest.service.FileSystemIngestService;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestSchedulerTest {

    @Mock
    private FileSystemIngestService fileSystemIngestService;

    @Mock
    private EmailIngestService emailIngestService;

    private IngestScheduler ingestScheduler;

    @BeforeEach
    void setUp() {
        IngestProperties ingestProperties = new IngestProperties();
        ingestProperties.setMaxFilesPerCycle(100);
        ingestScheduler = new IngestScheduler(
                ingestProperties, fileSystemIngestService, emailIngestService);
    }

    @Test
    void runCycle_triggersBothScannersWithSharedBudget() {
        when(fileSystemIngestService.ingestUpTo(100)).thenReturn(40);
        when(emailIngestService.ingestUpTo(60)).thenReturn(10);

        ingestScheduler.runCycle();

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
}
