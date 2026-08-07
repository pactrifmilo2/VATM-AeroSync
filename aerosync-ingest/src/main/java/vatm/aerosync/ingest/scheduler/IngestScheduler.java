package vatm.aerosync.ingest.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.ingest.config.IngestProperties;
import vatm.aerosync.ingest.service.EmailIngestService;
import vatm.aerosync.ingest.service.EmailAcknowledgementService;
import vatm.aerosync.ingest.service.FileSystemIngestService;
import vatm.aerosync.ingest.service.RuntimeIngestionConfig;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

@Component
public class IngestScheduler {

    private final IngestProperties ingestProperties;
    private final FileSystemIngestService fileSystemIngestService;
    private final EmailIngestService emailIngestService;
    private final EmailAcknowledgementService emailAcknowledgementService;
    private final RuntimeIngestionConfig runtimeIngestionConfig;
    private boolean legacyBothSources;
    private long lastEmailRun;
    private long lastFolderRun;

    @Autowired
    public IngestScheduler(IngestProperties ingestProperties,
                           FileSystemIngestService fileSystemIngestService,
                           EmailIngestService emailIngestService,
                           EmailAcknowledgementService emailAcknowledgementService,
                           RuntimeIngestionConfig runtimeIngestionConfig) {
        this.ingestProperties = ingestProperties;
        this.fileSystemIngestService = fileSystemIngestService;
        this.emailIngestService = emailIngestService;
        this.emailAcknowledgementService = emailAcknowledgementService;
        this.runtimeIngestionConfig = runtimeIngestionConfig;
        this.legacyBothSources = false;
    }

    /** Compatibility constructor for unit tests that exercise a single cycle. */
    public IngestScheduler(IngestProperties ingestProperties,
                           FileSystemIngestService fileSystemIngestService,
                           EmailIngestService emailIngestService,
                           EmailAcknowledgementService emailAcknowledgementService) {
        this(ingestProperties, fileSystemIngestService, emailIngestService,
                emailAcknowledgementService, null);
        this.legacyBothSources = true;
    }

    @Scheduled(fixedDelayString = "${app.ingest.scheduler-tick-ms:10000}")
    public void runCycle() {
        emailAcknowledgementService.retryPendingAcknowledgements();
        if (legacyBothSources) {
            int budget = ingestProperties.getMaxFilesPerCycle();
            int filesystemIngested = fileSystemIngestService.ingestUpTo(budget);
            int remaining = budget - filesystemIngested;
            int emailIngested = emailIngestService.ingestUpTo(remaining);
            DebugSessionLog.log("A", "IngestScheduler.java:runCycle", "legacy ingest cycle completed",
                    DebugSessionLog.map("budget", budget, "filesystemIngested", filesystemIngested,
                            "emailIngested", emailIngested));
            return;
        }
        RuntimeIngestionConfig.Settings settings = runtimeIngestionConfig == null
                ? new RuntimeIngestionConfig.Settings("EMAIL", ingestProperties.getSchedulerFixedDelayMs(),
                ingestProperties.getSchedulerFixedDelayMs(), ingestProperties.getMaxFilesPerCycle())
                : runtimeIngestionConfig.read();
        long now = System.currentTimeMillis();
        boolean folderEnabled = "FOLDER".equals(settings.mode()) || "BOTH".equals(settings.mode());
        boolean emailEnabled = "EMAIL".equals(settings.mode()) || "BOTH".equals(settings.mode());
        boolean folderDue = folderEnabled && now - lastFolderRun >= settings.folderIntervalMs();
        boolean emailDue = emailEnabled && now - lastEmailRun >= settings.emailIntervalMs();

        CompletableFuture<Integer> folderRun = CompletableFuture.completedFuture(0);
        CompletableFuture<Integer> emailRun = CompletableFuture.completedFuture(0);
        if (folderDue) {
            lastFolderRun = now;
            folderRun = runAsync("FOLDER",
                    () -> fileSystemIngestService.ingestUpTo(settings.maxFilesPerCycle()));
        }
        if (emailDue) {
            lastEmailRun = now;
            emailRun = runAsync("EMAIL",
                    () -> emailIngestService.ingestUpTo(settings.maxFilesPerCycle()));
        }
        int filesystemIngested = folderRun.join();
        int emailIngested = emailRun.join();
        DebugSessionLog.log("A", "IngestScheduler.java:runCycle", "ingest cycle completed",
                DebugSessionLog.map("mode", settings.mode(), "budget", settings.maxFilesPerCycle(),
                        "filesystemIngested", filesystemIngested,
                        "emailIngested", emailIngested));
    }

    private CompletableFuture<Integer> runAsync(String source, IntSupplier ingestAction) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ingestAction.getAsInt();
            } catch (RuntimeException exception) {
                DebugSessionLog.log("A", "IngestScheduler.java:runAsync", "ingest source failed",
                        DebugSessionLog.map("source", source,
                                "error", exception.getMessage()));
                return 0;
            }
        });
    }
}
