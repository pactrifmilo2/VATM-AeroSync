package vatm.aerosync.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;
import vatm.aerosync.worker.model.PermitFieldDiagnostic;
import vatm.aerosync.worker.model.PermitParseWarning;
import vatm.aerosync.worker.model.PermitProfileCandidate;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermitReviewCaptureServiceTest {

    @Mock
    private PermitReviewRepository permitReviewRepository;

    @Test
    void capturePersistsSnapshotAndAdaptiveDiagnostics() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PermitReviewCaptureService service = new PermitReviewCaptureService(
                permitReviewRepository,
                new PermitReviewSnapshotMapper(),
                objectMapper);
        when(permitReviewRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SchedulePermit permit = permit();
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                7L, "permit.docx", "f".repeat(64), FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        context.setWordPermitParseResult(new WordPermitParseResult(
                permit,
                "caav-adaptive",
                2,
                0.94,
                0.08,
                true,
                List.of(new PermitProfileCandidate(
                        "caav-adaptive", 2, 10, 0.94, 2, 3, true, true)),
                List.of(new PermitFieldDiagnostic("flightNumber", 0.95, "GLOBAL", "SHARED_ALIAS")),
                List.of(new PermitParseWarning(
                        "ADAPTIVE_HEADER_MATCH", "Shared alias used", true))));

        service.capture(new PermitImport(), context, "Adaptive match requires review");

        ArgumentCaptor<vatm.aerosync.common.entity.PermitReview> saved =
                ArgumentCaptor.forClass(vatm.aerosync.common.entity.PermitReview.class);
        verify(permitReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PermitReviewStatus.PENDING);
        assertThat(saved.getValue().getProfileId()).isEqualTo("caav-adaptive");
        assertThat(saved.getValue().getOriginalPermitJson())
                .contains("\"normalizedPermitId\":\"O/F 05199/S/CHK/2026\"");
        assertThat(saved.getValue().getWarningsJson()).contains("ADAPTIVE_HEADER_MATCH");
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
                false, false, true, "raw", List.of(flight));
    }
}
