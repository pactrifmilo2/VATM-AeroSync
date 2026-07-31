package vatm.aerosync.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.pipeline.AircraftTypeResolutionStep;
import vatm.aerosync.worker.pipeline.BusinessRuleValidatorStep;
import vatm.aerosync.worker.pipeline.NormalizerStep;
import vatm.aerosync.worker.pipeline.ViaResolutionStep;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermitReviewPublishingServiceTest {

    @Mock
    private PermitReviewRepository permitReviewRepository;
    @Mock
    private NormalizerStep normalizerStep;
    @Mock
    private AircraftTypeResolutionStep aircraftTypeResolutionStep;
    @Mock
    private ViaResolutionStep viaResolutionStep;
    @Mock
    private BusinessRuleValidatorStep businessRuleValidatorStep;
    @Mock
    private PermitImportCoordinator permitImportCoordinator;
    @Mock
    private AuditLogService auditLogService;

    @Test
    void publishUsesApprovedSnapshotAndRecordsPublishedState() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PermitReviewSnapshotMapper mapper = new PermitReviewSnapshotMapper();
        PermitReviewPublishingService service = new PermitReviewPublishingService(
                permitReviewRepository,
                mapper,
                normalizerStep,
                aircraftTypeResolutionStep,
                viaResolutionStep,
                businessRuleValidatorStep,
                permitImportCoordinator,
                auditLogService,
                objectMapper);

        SyncJob job = org.mockito.Mockito.mock(SyncJob.class);
        when(job.getId()).thenReturn(7L);
        PermitImport permitImport = org.mockito.Mockito.mock(PermitImport.class);
        when(permitImport.getSyncJob()).thenReturn(job);
        when(permitImport.getSourceFileHash()).thenReturn("f".repeat(64));

        PermitReview review = new PermitReview();
        review.setPermitImport(permitImport);
        review.setStatus(PermitReviewStatus.PUBLISHING);
        review.setCorrectedPermitJson(objectMapper.writeValueAsString(mapper.toSnapshot(permit())));
        when(permitReviewRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(review));
        when(permitImportCoordinator.publishApproved(any(), any()))
                .thenReturn(new PermitImportOutcome(PermitImportStatus.SAVED, 1, 10L, 20L));

        service.publish(new PermitReviewPublishCommand(
                4L, "admin.one", LocalDateTime.of(2026, 7, 30, 12, 0)));

        assertThat(review.getStatus()).isEqualTo(PermitReviewStatus.PUBLISHED);
        assertThat(review.getPublishedBy()).isEqualTo("admin.one");
        assertThat(review.getPublishedPermitJson()).contains("\"flightNumber\":\"RMY685\"");
        verify(permitImportCoordinator).publishApproved(any(), any());
        verify(permitReviewRepository).save(review);
    }

    @Test
    void staleFailureDoesNotOverwriteAReviewThatIsNoLongerPublishing() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PermitReviewPublishingService service = new PermitReviewPublishingService(
                permitReviewRepository,
                new PermitReviewSnapshotMapper(),
                normalizerStep,
                aircraftTypeResolutionStep,
                viaResolutionStep,
                businessRuleValidatorStep,
                permitImportCoordinator,
                auditLogService,
                objectMapper);
        PermitReview review = new PermitReview();
        review.setStatus(PermitReviewStatus.REJECTED);
        when(permitReviewRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(review));

        service.markFailed(new PermitReviewPublishCommand(
                4L, "admin.one", LocalDateTime.of(2026, 7, 30, 12, 0)),
                "stale delivery");

        assertThat(review.getStatus()).isEqualTo(PermitReviewStatus.REJECTED);
        verify(permitReviewRepository, never()).save(any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyLong());
    }

    private SchedulePermit permit() {
        ScheduleFlight flight = new ScheduleFlight(
                "CAR", 1935L, BigDecimal.ZERO, "RMY685", null, "1000000",
                "WMKK", "VHHH", "1140", null, "M765/M771",
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27),
                "CAR 76X/32X");
        return new SchedulePermit(
                "OF-5199/7/2026VN", "O/F 05199/S/CHK/2026", "5199",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 7, 17),
                "RMY", "G17.44", 72, "Cyberjaya", "SC",
                false, false, false, "raw", List.of(flight));
    }
}
