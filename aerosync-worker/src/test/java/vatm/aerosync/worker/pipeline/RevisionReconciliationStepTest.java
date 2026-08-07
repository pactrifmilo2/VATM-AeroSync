package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RevisionReconciliationStepTest {

    @Test
    void reconcile_keepsReplacementRowsWithoutAtfmLookup() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = permit(List.of(flight("NEW100")), List.of(flight("OLD100")));
        ProcessingContext context = context(revision);

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly("NEW100");
        assertThat(context.getSchedulePermit().originalFlights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly("OLD100");
        verifyNoInteractions(gateway);
    }

    @Test
    void reconcile_doesNotFailWhenRevisionBaseIsMissing() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = permit(List.of(flight("NEW100")), List.of());

        new RevisionReconciliationStep(gateway).reconcile(context(revision));

        assertThat(revision.flights()).hasSize(1);
        verifyNoInteractions(gateway);
    }

    private ProcessingContext context(SchedulePermit permit) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                7L, "C:/revision.docx", "a".repeat(64), FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        return context;
    }

    private SchedulePermit permit(List<ScheduleFlight> flights, List<ScheduleFlight> originals) {
        return new SchedulePermit(
                "OF-5517/8/2026VN/REV1", "O/F 05517/S/CHK/2026", "5517",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 8, 3),
                "FDX", null, 72, null, "SC", false, false, true, "REV1 raw",
                flights, originals);
    }

    private ScheduleFlight flight(String number) {
        return new ScheduleFlight(
                "CAR", 0L, null, number, null, "0000007", "VVNB", "VVTS",
                "0300", "1540", null, LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 16), null, null);
    }
}
