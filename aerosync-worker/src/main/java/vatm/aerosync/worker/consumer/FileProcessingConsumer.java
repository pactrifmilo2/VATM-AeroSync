package vatm.aerosync.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.worker.pipeline.FileProcessingPipeline;
import vatm.aerosync.worker.service.FileProcessingLockService;

@Component
public class FileProcessingConsumer {

    private static final Logger log = LoggerFactory.getLogger(FileProcessingConsumer.class);

    private final FileProcessingLockService lockService;
    private final FileProcessingPipeline pipeline;

    public FileProcessingConsumer(FileProcessingLockService lockService,
                                  FileProcessingPipeline pipeline) {
        this.lockService = lockService;
        this.pipeline = pipeline;
    }

    @RabbitListener(queues = "${app.rabbit.file-processing-queue}")
    public void onFileIngested(FileIngestedEvent event) {
        DebugSessionLog.log("C", "FileProcessingConsumer.java:onFileIngested", "message received",
                DebugSessionLog.map("syncJobId", event.getSyncJobId(), "path", event.getTempFilePath()));
        if (!lockService.tryAcquire(event.getSyncJobId())) {
            DebugSessionLog.log("C", "FileProcessingConsumer.java:onFileIngested", "lock not acquired, skipping",
                    DebugSessionLog.map("syncJobId", event.getSyncJobId()));
            log.warn("Skipping sync job {} — lock held by another instance", event.getSyncJobId());
            return;
        }
        try {
            pipeline.process(event);
            DebugSessionLog.log("E", "FileProcessingConsumer.java:onFileIngested", "pipeline completed",
                    DebugSessionLog.map("syncJobId", event.getSyncJobId()));
        } finally {
            lockService.release(event.getSyncJobId());
        }
    }
}
