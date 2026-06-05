package vatm.aerosync.worker.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.pipeline.FileProcessingPipeline;
import vatm.aerosync.worker.service.FileProcessingLockService;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileProcessingConsumerTest {

    @Mock
    private FileProcessingLockService lockService;

    @Mock
    private FileProcessingPipeline pipeline;

    @InjectMocks
    private FileProcessingConsumer consumer;

    @Test
    void onFileIngested_processesWhenLockAcquired() {
        FileIngestedEvent event = new FileIngestedEvent(5L, "/tmp/a.csv", "h", FileSourceType.EMAIL, false);
        when(lockService.tryAcquire(5L)).thenReturn(true);

        consumer.onFileIngested(event);

        verify(pipeline).process(event);
        verify(lockService).release(5L);
    }

    @Test
    void onFileIngested_skipsWhenLockNotAcquired() {
        FileIngestedEvent event = new FileIngestedEvent(5L, "/tmp/a.csv", "h", FileSourceType.EMAIL, false);
        when(lockService.tryAcquire(5L)).thenReturn(false);

        consumer.onFileIngested(event);

        verify(pipeline, never()).process(event);
        verify(lockService, never()).release(5L);
    }
}
