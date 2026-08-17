package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevisionReconciliationStepTest {

    @Test
    void reconcile_usesRevisionNumberFromFileNameWhenWordHasNewTableWithoutRevMarker() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = implicitRevisionPermit();
        ProcessingContext context = context(revision, "OF-78_R(7)_bang-moi.docx");
        SchedulePermit numbered = revision.withRevisionNumber(7);
        when(gateway.findRevisionBaseline(numbered)).thenReturn(java.util.Optional.empty());

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().revisionNumber()).isEqualTo(7);
        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("O/F 00078BR7/S/CHK/2026");
    }

    @Test
    void reconcile_allocatesFirstUnusedRevisionNumberWhenFileNameHasNoNumber() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = implicitRevisionPermit();
        ProcessingContext context = context(revision, "OF-78_bang-moi.docx");
        when(gateway.findUsedRevisionNumbers(revision)).thenReturn(Set.of(1, 2, 3));
        SchedulePermit numbered = revision.withRevisionNumber(4);
        when(gateway.findRevisionBaseline(numbered)).thenReturn(java.util.Optional.empty());

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().revisionNumber()).isEqualTo(4);
        assertThat(context.getSchedulePermit().permitNumber()).isEqualTo("78BR4");
        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("O/F 00078BR4/S/CHK/2026");
    }

    @Test
    void reconcile_doesNotInferRevisionForNonSeasonalPermit() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = new SchedulePermit(
                "OF-78/B/2026VN", "O/F 00078B/CHK/2026", "78B",
                "CHK", "O/F", "B", null, LocalDate.of(2026, 8, 3),
                "FDX", null, 72, null, "NO", false, false, true,
                "NEW FLIGHT SCHEDULE", List.of(flight("NEW100")), List.of());
        ProcessingContext context = context(revision, "OF-78_REV5_new-schedule.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().revisionNumber()).isNull();
        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("O/F 00078B/CHK/2026");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never())
                .findUsedRevisionNumbers(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconcile_keepsReplacementRowsWhenAtfmBaseIsMissing() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = permit(List.of(flight("NEW100")), List.of(flight("OLD100")));
        ProcessingContext context = context(revision);
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.empty());

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly("OLD100", "NEW100");
        assertThat(context.getSchedulePermit().flights().getFirst().remark())
                .isEqualTo("HUY TU O/F 05517A/S/CHK/2026");
        assertThat(context.getSchedulePermit().originalFlights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly("OLD100");
    }

    @Test
    void reconcile_doesNotFailWhenRevisionBaseIsMissing() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = permit(List.of(flight("NEW100")), List.of());
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.empty());

        new RevisionReconciliationStep(gateway).reconcile(context(revision));

        assertThat(revision.flights()).hasSize(1);
    }

    @Test
    void reconcile_inheritsMissingRouteAndDetailsFromOriginalPermit() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = permit(List.of(flight("NEW100")), List.of(flight("OLD100")));
        ScheduleFlight original = new ScheduleFlight(
                "CAR", 1935L, java.math.BigDecimal.valueOf(75), "OLD100", "VN-A123",
                "1000000", "VVNB", "VVTS", "0300", "0500", "W1/DAN/W2",
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 16), "Giữ nguyên", null);
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(1L, 2L, "FDX", List.of(original))));
        ProcessingContext context = context(revision);

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).hasSize(2);
        assertThat(context.getSchedulePermit().flights().getFirst()).satisfies(flight -> {
            assertThat(flight.via()).isEqualTo("W1/DAN/W2");
            assertThat(flight.registration()).isEqualTo("VN-A123");
            assertThat(flight.craftId()).isEqualTo(1935L);
            assertThat(flight.remark()).isEqualTo("HUY TU O/F 05517A/S/CHK/2026");
        });
    }

    @Test
    void reconcile_nonScheduledRevisionWithSameIdentityAppendsToOriginalPermit() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN123", "VVTS", "321");
        SchedulePermit revision = nonScheduledPermit(revised, original);
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(original))));
        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.revisionCreatesNewPermit()).isFalse();
        assertThat(result.atfmTargetPermitId()).isEqualTo("LD 02576A/CHK/2026");
        assertThat(result.revisionReference()).isEqualTo("13082026 RVS(1)");
        assertThat(result.flights()).singleElement().satisfies(flight ->
                assertThat(flight.remark()).isEqualTo("13082026 RVS(1)"));
    }

    @Test
    void reconcile_nonScheduledRevisionWithNewBusinessKeyInsertsIntoOriginalPermit() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN123", "VVDN", "321");
        SchedulePermit revision = nonScheduledPermit(revised, original);
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(original))));
        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.revisionCreatesNewPermit()).isFalse();
        assertThat(result.atfmTargetPermitId()).isEqualTo("LD 02576A/CHK/2026");
        assertThat(result.permitNumber()).isEqualTo("2576A");
        assertThat(result.flights()).extracting(ScheduleFlight::remark)
                .containsExactly("PAX 321");
    }

    @Test
    void reconcile_nonScheduledRevisionWithoutBaselineStillTargetsOriginalPermit() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("CKS602", "VVNB", "B744");
        ScheduleFlight revised = nonScheduledFlight("CKS9716", "VVNB", "B744");
        SchedulePermit revision = nonScheduledPermit(revised, original);
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.empty());
        ProcessingContext context = context(revision, "REV1-LD-2797_K4.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit result = context.getSchedulePermit();
        assertThat(result.seasonalPermit()).isFalse();
        assertThat(result.revisionCreatesNewPermit()).isFalse();
        assertThat(result.atfmTargetPermitId()).isEqualTo("LD 02576A/CHK/2026");
        assertThat(result.flights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly("CKS9716");
    }

    @Test
    void reconcile_appliesSameNonScheduledDecisionToVietnameseRevision() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN123", "VVTS", "321");
        SchedulePermit revision = nonScheduledPermit(
                revised, original,
                "RVS1\nBẢNG LỊCH BAY SỬA ĐỔI\n"
                        + "Các thông tin khác không thay đổi");
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(original))));
        ProcessingContext context = context(revision, "LD-2576-SUA-DOI-1.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().revisionCreatesNewPermit()).isFalse();
        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("LD 02576A/CHK/2026");
        assertThat(context.getSchedulePermit().flights()).singleElement().satisfies(flight ->
                assertThat(flight.remark()).isEqualTo("13082026 RVS(1)"));
    }

    @Test
    void reconcile_seasonalRevisionUsesOnlyCancellationAndAircraftRemarks() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN124", "VVTS", "321");
        SchedulePermit revision = new SchedulePermit(
                "LD-32/B/S/2026VN/REV2", "LD 00032B/S/CHK/2026", "32B",
                "CHK", "LD", "B", "S", LocalDate.of(2026, 8, 13),
                "HVN", "LD-32/B/S/2026VN", 24, "Vietnam Airlines", "SC",
                false, false, false, "REV2\nNEW FLIGHT SCHEDULE",
                List.of(revised), List.of(original));
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(original))));
        ProcessingContext context = context(revision, "LD-32-REV2.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("LD 00032BR2/S/CHK/2026");
        assertThat(context.getSchedulePermit().reference())
                .isEqualTo("LD-32/B/S/2026VN RVS(2)");
        assertThat(context.getSchedulePermit().flights()).extracting(ScheduleFlight::remark)
                .containsExactly("HUY TU LD-32/B/S/2026VN", "PAX 321");
    }

    @Test
    void reconcile_onlyCancelsOriginalRowsListedInWordAndEnrichesByFlightAndRoute() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight documentedOriginal = new ScheduleFlight(
                null, 0L, null, "MAS523", null, "0004000",
                "ZGSZ", "WMKK", "1845", "2245", null,
                LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 13), null, null);
        ScheduleFlight revised = new ScheduleFlight(
                null, 0L, null, "MAS523", null, "0004000",
                "ZGSZ", "WMKK", "1745", "2145", null,
                LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 13), null, null);
        ScheduleFlight matchedBaseline = new ScheduleFlight(
                "CAR", 738L, java.math.BigDecimal.valueOf(79), "MAS523", "9M-MXA", "0004000",
                "ZGSZ", "WMKK", "1845", "2245", "A1 B2",
                LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 13), "CAR B738", null);
        ScheduleFlight unrelatedBaseline = nonScheduledFlight("MAS038", "RKSI", "333");
        SchedulePermit revision = new SchedulePermit(
                "OF-20/A/S/2026VN/REV1", "O/F 0020A/S/CHK/2026", "20A",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 8, 14),
                "MAS", "O/F 0020A/S/CHK/2026", 72, "Malaysia Airlines", "SC",
                false, true, false,
                "REV1\nNEW SCHEDULE\nOther details unchanged",
                List.of(revised), List.of(documentedOriginal));
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(
                        10L, 20L, "MAS", List.of(matchedBaseline, unrelatedBaseline))));
        ProcessingContext context = context(revision, "OF-20A-r1.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).hasSize(2);
        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("MAS523", "MAS523")
                .doesNotContain("MAS038");
        assertThat(context.getSchedulePermit().flights().getFirst()).satisfies(flight -> {
            assertThat(flight.craftId()).isEqualTo(738L);
            assertThat(flight.purposeId()).isEqualTo("CAR");
            assertThat(flight.via()).isEqualTo("A1 B2");
            assertThat(flight.remark()).isEqualTo("HUY TU O/F 0020A/S/CHK/2026");
        });
    }

    @Test
    void reconcile_doesNotCopyBaselineRowsWhenWordHasNoOriginalScheduleTable() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight revised = nonScheduledFlight("HVN999", "VVTS", "321");
        SchedulePermit revision = new SchedulePermit(
                "LD-32/B/S/2026VN/REV3", "LD 00032B/S/CHK/2026", "32B",
                "CHK", "LD", "B", "S", LocalDate.of(2026, 8, 14),
                "HVN", "LD-32/B/S/2026VN", 24, "Vietnam Airlines", "SC",
                false, false, false, "REV3\nNEW FLIGHT SCHEDULE",
                List.of(revised), List.of());
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(
                        nonScheduledFlight("HVN100", "VVTS", "320"),
                        nonScheduledFlight("HVN200", "VVDN", "321")))));
        ProcessingContext context = context(revision, "LD-32-REV3.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("HVN999");
    }

    private ProcessingContext context(SchedulePermit permit) {
        return context(permit, "revision.docx");
    }

    private ProcessingContext context(SchedulePermit permit, String fileName) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                7L, "C:/" + fileName, "a".repeat(64), FileSourceType.EMAIL, false));
        context.setFilePath(java.nio.file.Path.of("C:/" + fileName));
        context.setSchedulePermit(permit);
        return context;
    }

    private SchedulePermit implicitRevisionPermit() {
        return new SchedulePermit(
                "OF-78/B/S/2026VN", "O/F 00078B/S/CHK/2026", "78B",
                "CHK", "O/F", "B", "S", LocalDate.of(2026, 8, 3),
                "FDX", null, 72, null, "SC", false, false, true,
                "BẢNG PHÉP BAY MỚI", List.of(flight("NEW100")), List.of());
    }

    private SchedulePermit permit(List<ScheduleFlight> flights, List<ScheduleFlight> originals) {
        return new SchedulePermit(
                "OF-5517/A/S/2026VN/REV1", "O/F 05517A/S/CHK/2026", "5517A",
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

    private SchedulePermit nonScheduledPermit(ScheduleFlight revised, ScheduleFlight original) {
        return nonScheduledPermit(
                revised, original,
                "RVS1\nREVISED FLIGHT SCHEDULE\nOther information remains unchanged");
    }

    private SchedulePermit nonScheduledPermit(ScheduleFlight revised,
                                               ScheduleFlight original,
                                               String rawContent) {
        return new SchedulePermit(
                "LD-2576/8/2026VN /RVS1", "LD 02576A/CHK/2026", "2576A",
                "CHK", "LD", "A", null, LocalDate.of(2026, 8, 13),
                "HVN", "LD-2576/A/2026VN", 24, "Vietnam Airlines", "NO",
                false, false, false,
                rawContent,
                List.of(revised), List.of(original));
    }

    private ScheduleFlight nonScheduledFlight(String number, String destination, String aircraft) {
        return new ScheduleFlight(
                "PAX", 1935L, java.math.BigDecimal.ZERO, number, null, "0001000",
                "VVNB", destination, "0100", "0300", null,
                LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12),
                "PAX " + aircraft, aircraft);
    }
}
