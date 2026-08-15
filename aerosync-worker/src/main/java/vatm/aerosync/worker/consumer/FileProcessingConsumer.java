package vatm.aerosync.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.pipeline.FileProcessingPipeline;
import vatm.aerosync.worker.service.FileProcessingLockService;

@Component
public class FileProcessingConsumer {

    private static final Logger log = LoggerFactory.getLogger(FileProcessingConsumer.class);

    private final FileProcessingLockService lockService;
    private final FileProcessingPipeline pipeline;
    private final SyncJobRepository syncJobRepository;

    public FileProcessingConsumer(FileProcessingLockService lockService,
                                  FileProcessingPipeline pipeline,
                                  SyncJobRepository syncJobRepository) {
        this.lockService = lockService;
        this.pipeline = pipeline;
        this.syncJobRepository = syncJobRepository;
    }

    @RabbitListener(queues = "${app.rabbit.file-processing-queue}")
    public void onFileIngested(FileIngestedEvent event) {
        validateEvent(event);
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

    private void validateEvent(FileIngestedEvent event) {
        if (event == null) {
            reject("Bản tin xử lý file rỗng nên không thể xác định tác vụ.");
        }
        if (event.getSyncJobId() == null) {
            reject("Bản tin xử lý file thiếu mã tác vụ liên kết.");
        }
        if (event.getTempFilePath() == null || event.getTempFilePath().isBlank()) {
            reject("Tác vụ " + event.getSyncJobId() + " không có đường dẫn file cần xử lý.");
        }
        if (!syncJobRepository.existsById(event.getSyncJobId())) {
            reject("Không tìm thấy tác vụ xử lý tương ứng với mã " + event.getSyncJobId() + ".");
        }
    }

    private void reject(String message) {
        log.error("Loại bỏ bản tin RabbitMQ không hợp lệ: {}", message);
        throw new AmqpRejectAndDontRequeueException(message);
    }
}
