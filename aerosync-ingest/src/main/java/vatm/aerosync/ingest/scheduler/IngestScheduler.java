package vatm.aerosync.ingest.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.ingest.config.IngestProperties;
import vatm.aerosync.ingest.service.EmailIngestService;
import vatm.aerosync.ingest.service.FileSystemIngestService;

@Component
public class IngestScheduler {

    private final IngestProperties ingestProperties;
    private final FileSystemIngestService fileSystemIngestService;
    private final EmailIngestService emailIngestService;

    public IngestScheduler(IngestProperties ingestProperties,
                           FileSystemIngestService fileSystemIngestService,
                           EmailIngestService emailIngestService) {
        this.ingestProperties = ingestProperties;
        this.fileSystemIngestService = fileSystemIngestService;
        this.emailIngestService = emailIngestService;
    }

    @Scheduled(fixedDelayString = "${app.ingest.scheduler-fixed-delay-ms}")
    public void runCycle() {
        int budget = ingestProperties.getMaxFilesPerCycle();
        int filesystemIngested = fileSystemIngestService.ingestUpTo(budget);
        int remaining = budget - filesystemIngested;
        int emailIngested = emailIngestService.ingestUpTo(remaining);
        DebugSessionLog.log("A", "IngestScheduler.java:runCycle", "ingest cycle completed",
                DebugSessionLog.map("budget", budget, "filesystemIngested", filesystemIngested,
                        "emailIngested", emailIngested));
    }
}
