package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevisionReconciliationStepTest {

    @Test
    void reconcile_inheritsUnchangedAircraftAndRouteFromAtfm() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = flight(1935L, BigDecimal.valueOf(79000), "FDX9082", "WSSS", "PANC", "M765");
        SchedulePermit revision = permit(List.of(flight(0L, null, "FDX9082", "WSSS", "PANC", null)));
        when(gateway.findRevisionBaseline(revision))
                .thenReturn(Optional.of(new AtfmRevisionBaseline(10L, 20L, List.of(original))));
        ProcessingContext context = context(revision);

        new RevisionReconciliationStep(gateway).reconcile(context);

        ScheduleFlight reconciled = context.getSchedulePermit().flights().getFirst();
        assertThat(reconciled.craftId()).isEqualTo(1935L);
        assertThat(reconciled.mtow()).isEqualByComparingTo("79000");
        assertThat(reconciled.via()).isEqualTo("M765");
    }

    @Test
    void reconcile_usesDeclaredOriginalAndReturnsOnlyRowsToAppend() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight unchanged = flight(99L, BigDecimal.valueOf(1000),
                "FDX1000", "VVNB", "VVTS", "W1");
        ScheduleFlight original = flight(1935L, BigDecimal.valueOf(79000),
                "FDX9082", "WSSS", "PANC", "M765");
        ScheduleFlight replacement = flight(0L, null,
                "FDX9082", "WSSS", "RCTP", null);
        SchedulePermit revision = permit(List.of(replacement), List.of(original));
        when(gateway.findRevisionBaseline(revision)).thenReturn(Optional.of(
                new AtfmRevisionBaseline(10L, 20L, List.of(unchanged, original))));

        ProcessingContext context = context(revision);
        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).hasSize(1);
        assertThat(context.getSchedulePermit().flights().getFirst().toAirport()).isEqualTo("RCTP");
        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(1935L);
    }

    @Test
    void reconcile_rejectsAmbiguousAtfmBaseline() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = permit(List.of(flight(0L, null, "NEW100", "VVNB", "VVTS", null)));
        when(gateway.findRevisionBaseline(revision)).thenReturn(Optional.of(
                new AtfmRevisionBaseline(10L, 20L, List.of(
                        flight(1L, BigDecimal.ZERO, "OLD100", "VVNB", "VVDN", "A1"),
                        flight(2L, BigDecimal.ZERO, "OLD200", "VVDN", "VVTS", "W1")))));

        assertThatThrownBy(() -> new RevisionReconciliationStep(gateway).reconcile(context(revision)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-REVISION-BASE-AMBIGUOUS");
    }

    private ProcessingContext context(SchedulePermit permit) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                7L, "C:/revision.docx", "a".repeat(64), FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        return context;
    }

    private SchedulePermit permit(List<ScheduleFlight> flights) {
        return permit(flights, List.of());
    }

    private SchedulePermit permit(List<ScheduleFlight> flights, List<ScheduleFlight> originals) {
        return new SchedulePermit(
                "OF-5517/8/2026VN/REV1", "O/F 05517/S/CHK/2026", "5517",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 8, 3),
                "FDX", null, 72, null, "SC", false, false, true, "REV1 raw",
                flights, originals);
    }

    private ScheduleFlight flight(long craftId,
                                  BigDecimal mtow,
                                  String number,
                                  String from,
                                  String to,
                                  String via) {
        return new ScheduleFlight(
                "CAR", craftId, mtow, number, null, "0000007", from, to,
                "0300", "1540", via, LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 16), null, null);
    }
}
