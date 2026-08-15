package vatm.aerosync.worker.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.pipeline.FileProcessingPipeline;
import vatm.aerosync.worker.service.FileProcessingLockService;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

@ExtendWith(MockitoExtension.class)
class FileProcessingConsumerTest {

    @Mock
    private FileProcessingLockService lockService;

    @Mock
    private FileProcessingPipeline pipeline;

    @Mock
    private SyncJobRepository syncJobRepository;

    @InjectMocks
    private FileProcessingConsumer consumer;

    @Test
    void onFileIngested_processesWhenLockAcquired() {
        FileIngestedEvent event = new FileIngestedEvent(5L, "/tmp/a.csv", "h", FileSourceType.EMAIL, false);
        when(syncJobRepository.existsById(5L)).thenReturn(true);
        when(lockService.tryAcquire(5L)).thenReturn(true);

        consumer.onFileIngested(event);

        verify(pipeline).process(event);
        verify(lockService).release(5L);
    }

    @Test
    void onFileIngested_skipsWhenLockNotAcquired() {
        FileIngestedEvent event = new FileIngestedEvent(5L, "/tmp/a.csv", "h", FileSourceType.EMAIL, false);
        when(syncJobRepository.existsById(5L)).thenReturn(true);
        when(lockService.tryAcquire(5L)).thenReturn(false);

        consumer.onFileIngested(event);

        verify(pipeline, never()).process(event);
        verify(lockService, never()).release(5L);
    }

    @Test
    void onFileIngested_rejectsMessageWithoutSyncJobIdWithoutRequeue() {
        FileIngestedEvent event = new FileIngestedEvent(null, "/tmp/a.csv", "h", FileSourceType.EMAIL, false);

        assertThatThrownBy(() -> consumer.onFileIngested(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("Bản tin xử lý file thiếu mã tác vụ liên kết.");

        verify(pipeline, never()).process(event);
        verify(lockService, never()).tryAcquire(null);
    }

    @Test
    void onFileIngested_rejectsMessageForDeletedJobWithoutRequeue() {
        FileIngestedEvent event = new FileIngestedEvent(99L, "/tmp/a.csv", "h", FileSourceType.EMAIL, false);
        when(syncJobRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> consumer.onFileIngested(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("Không tìm thấy tác vụ xử lý tương ứng với mã 99.");

        verify(pipeline, never()).process(event);
    }
}
