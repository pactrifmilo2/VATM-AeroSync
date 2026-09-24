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
    void reconcile_allocatesRevisionNumberForBareSeasonalRvsMarker() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = new SchedulePermit(
                "OF-78/B/S/2026VN/RVS", "O/F 00078B/S/CHK/2026", "78B",
                "CHK", "O/F", "B", "S", LocalDate.of(2026, 8, 3),
                "FDX", null, 72, null, "SC", false, false, true,
                "OTHER DETAILS REMAIN UNCHANGED", List.of(flight("NEW100")), List.of());
        ProcessingContext context = context(revision, "OF-78-rvs.docx");
        when(gateway.findUsedRevisionNumbers(revision)).thenReturn(Set.of(1, 2));
        SchedulePermit numbered = revision.withRevisionNumber(3);
        when(gateway.findRevisionBaseline(numbered)).thenReturn(java.util.Optional.empty());

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().revisionNumber()).isEqualTo(3);
        assertThat(context.getSchedulePermit().permitNumber()).isEqualTo("78BR3");
        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("O/F 00078BR3/S/CHK/2026");
    }

    @Test
    void reconcile_infersRevisionForNonSeasonalReplacementTable() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        SchedulePermit revision = new SchedulePermit(
                "OF-78/B/2026VN", "O/F 00078B/CHK/2026", "78B",
                "CHK", "O/F", "B", null, LocalDate.of(2026, 8, 3),
                "FDX", null, 72, null, "NO", false, false, true,
                "NEW FLIGHT SCHEDULE", List.of(flight("NEW100")), List.of());
        ProcessingContext context = context(revision, "OF-78_REV5_new-schedule.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().revisionNumber()).isEqualTo(5);
        assertThat(context.getSchedulePermit().atfmTargetPermitId())
                .isEqualTo("O/F 00078B/CHK/2026");
        assertThat(context.getSchedulePermit().importRevision()).isTrue();
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
        assertThat(result.revisionReference()).isEqualTo("EM 13082026 R(1)");
        assertThat(result.flights()).singleElement().satisfies(flight ->
                assertThat(flight.remark()).isEqualTo("13082026 R(1)"));
    }

    @Test
    void reconcile_nonScheduledRevisionWithSameCallsignUpdatesOriginalPermit() {
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
                .containsExactly("13082026 R(1)");
    }

    @Test
    void reconcile_marksExactDateWhenCallsignAppearsMultipleTimesInBaseline() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight documentedOriginal = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN123", "VVTS", "321");
        ScheduleFlight anotherDate = new ScheduleFlight(
                "PAX", 1935L, java.math.BigDecimal.ZERO, "HVN123", null, "0001000",
                "VVNB", "VVTS", "0100", "0300", null,
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 11), "PAX 320", "320");
        SchedulePermit revision = nonScheduledPermit(revised, documentedOriginal);
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(anotherDate, documentedOriginal))));

        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");
        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).singleElement()
                .extracting(ScheduleFlight::remark)
                .isEqualTo("13082026 R(1)");
    }

    @Test
    void reconcile_inheritsAircraftByCallsignWhenAircraftTableIsAbsent() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = new ScheduleFlight(
                null, 0L, null, "HVN123", null, "0001000", "VVNB", "VVDN",
                "0100", "0300", null, LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 12), null, null);
        SchedulePermit revision = nonScheduledPermit(
                revised, original, "RVS1\n3.1 Original\n3.2 New");
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.empty());

        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");
        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).singleElement().satisfies(flight -> {
            assertThat(flight.craftId()).isEqualTo(1935L);
            assertThat(flight.sourceAircraftType()).isEqualTo("320");
            assertThat(flight.remark()).isEqualTo("320");
        });
    }

    @Test
    void reconcile_doesNotInheritAircraftWhenAircraftTableIsPresent() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = new ScheduleFlight(
                null, 0L, null, "HVN123", null, "0001000", "VVNB", "VVDN",
                "0100", "0300", null, LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 12), null, null, true);
        SchedulePermit revision = nonScheduledPermit(
                revised, original, "RVS1\nOther main details is unchanged");
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.empty());

        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");
        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).singleElement().satisfies(flight -> {
            assertThat(flight.craftId()).isZero();
            assertThat(flight.sourceAircraftType()).isNull();
        });
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
                assertThat(flight.remark()).isEqualTo("13082026 R(1)"));
    }

    @Test
    void reconcile_inheritsBaselineViaForSameRouteWithoutUnchangedStatement() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN123", "VVTS", "321");
        ScheduleFlight baseline = new ScheduleFlight(
                "PAX", 1935L, java.math.BigDecimal.ZERO, "HVN123", null, "0001000",
                "VVNB", "VVTS", "0100", "0300", "W1/DAN/W2",
                LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), "PAX 320", "320");
        SchedulePermit revision = nonScheduledPermit(
                revised, original, "RVS1\nREVISED FLIGHT SCHEDULE");
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(baseline))));
        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(revision.otherDetailsUnchanged()).isFalse();
        assertThat(context.getSchedulePermit().flights()).singleElement()
                .extracting(ScheduleFlight::via)
                .isEqualTo("W1/DAN/W2");
    }

    @Test
    void reconcile_doesNotInheritBaselineViaWhenRouteChanges() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("HVN123", "VVTS", "320");
        ScheduleFlight revised = nonScheduledFlight("HVN123", "VVDN", "321");
        ScheduleFlight baseline = new ScheduleFlight(
                "PAX", 1935L, java.math.BigDecimal.ZERO, "HVN123", null, "0001000",
                "VVNB", "VVTS", "0100", "0300", "W1/DAN/W2",
                LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), "PAX 320", "320");
        SchedulePermit revision = nonScheduledPermit(
                revised, original, "RVS1\nREVISED FLIGHT SCHEDULE");
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "HVN", List.of(baseline))));
        ProcessingContext context = context(revision, "LD-2576-RVS1.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).singleElement()
                .satisfies(flight -> {
                    assertThat(flight.toAirport()).isEqualTo("VVDN");
                    assertThat(flight.via()).isNull();
                });
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
                .isEqualTo("LD-32/B/S/2026VN");
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
    void reconcile_normalizesIataCallsignBeforeInheritingAircraftFromBaseline() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("9G907", "VVNB", null)
                .withResolvedAircraft(0L, null);
        ScheduleFlight revised = nonScheduledFlight("9G907", "VVNB", null)
                .withResolvedAircraft(0L, null);
        ScheduleFlight baseline = nonScheduledFlight("SPQ907", "VVNB", "A321")
                .withResolvedAircraft(4366L, java.math.BigDecimal.valueOf(93.5));
        baseline = new ScheduleFlight(
                baseline.purposeId(), baseline.craftId(), baseline.mtow(), baseline.flightNumber(),
                baseline.registration(), baseline.serviceDays(), baseline.fromAirport(),
                baseline.toAirport(), baseline.etd(), baseline.eta(), baseline.via(),
                baseline.beginDate().minusDays(30), baseline.endDate().plusDays(30),
                baseline.remark(), baseline.sourceAircraftType());
        SchedulePermit revision = new SchedulePermit(
                "LD-32/C/S/2026VN/REV17", "LD 0032C/S/CHK/2026", "32C",
                "CHK", "LD", "C", "S", LocalDate.of(2026, 9, 5),
                "SPQ", "LD-32/C/S/2026VN", 24, "SPQ", "SC",
                true, false, false, "REV17\nCác chi tiết khác không thay đổi",
                List.of(revised), List.of(original));
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(1L, 2L, "SPQ", List.of(baseline))));

        ProcessingContext context = context(revision, "SPA022-REV17.docx");
        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights().getLast()).satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("SPQ907");
            assertThat(flight.craftId()).isEqualTo(4366L);
        });
        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::craftId)
                .containsOnly(4366L);
    }

    @Test
    void reconcile_inheritsAircraftWhenRepeatedBaselineRowsAgreeOnCraft() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight original = nonScheduledFlight("SPQ907", "VVNB", null)
                .withResolvedAircraft(0L, null);
        ScheduleFlight revised = nonScheduledFlight("SPQ907", "VVNB", null)
                .withResolvedAircraft(0L, null);
        ScheduleFlight first = nonScheduledFlight("SPQ907", "VVNB", "A321")
                .withResolvedAircraft(4366L, java.math.BigDecimal.valueOf(93.5));
        ScheduleFlight second = new ScheduleFlight(
                first.purposeId(), first.craftId(), first.mtow(), first.flightNumber(),
                first.registration(), first.serviceDays(), first.fromAirport(), first.toAirport(),
                first.etd(), first.eta(), first.via(), first.beginDate().plusDays(1),
                first.endDate().plusDays(1), first.remark(), first.sourceAircraftType());
        SchedulePermit revision = new SchedulePermit(
                "LD-32/C/S/2026VN/REV17", "LD 0032C/S/CHK/2026", "32C",
                "CHK", "LD", "C", "S", LocalDate.of(2026, 9, 5),
                "SPQ", "LD-32/C/S/2026VN", 24, "SPQ", "SC",
                true, false, false, "REV17\nCác chi tiết khác không thay đổi",
                List.of(revised), List.of(original));
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(1L, 2L, "SPQ", List.of(first, second))));

        ProcessingContext context = context(revision, "SPA022-REV17.docx");
        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::craftId)
                .containsOnly(4366L);
    }

    @Test
    void reconcile_inheritsAircraftByDocumentedOriginalWhenCallsignAndRouteBothChange() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight documentedOriginal = new ScheduleFlight(
                null, 0L, null, "THY6244", null, "0000060",
                "IST", "BEY", "1910", "2115", null,
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5), null, null);
        ScheduleFlight revised = new ScheduleFlight(
                null, 0L, null, "THY6242", null, "0000060",
                "CAI", "HAN", "2000", "0810", "B214 W21",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5), null, null);
        ScheduleFlight baseline = new ScheduleFlight(
                "CAR", 24L, java.math.BigDecimal.valueOf(100), "THY6244", null,
                "0000060", "OLBA", "VVNB", "2305", "0810+", "B214/W21",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "CAR 77X", null);
        SchedulePermit revision = new SchedulePermit(
                "LD-25/B/S/2026VN/REV30", "LD 0025B/S/CHK/2026", "25B",
                "CHK", "LD", "B", "S", LocalDate.of(2026, 8, 26),
                "THY", "LD-25/B/S/2026VN", 24, "Turkish Airlines Cargo", "SC",
                true, true, false, "REV30\nOther details unchanged",
                List.of(revised), List.of(documentedOriginal));
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(1L, 2L, "THY", List.of(baseline))));

        ProcessingContext context = context(revision, "REV30-LD-25B.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        assertThat(context.getSchedulePermit().flights()).hasSize(2);
        assertThat(context.getSchedulePermit().flights().getLast().craftId()).isEqualTo(24L);
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

    @Test
    void reconcile_routesDatedRevisionToItsLegacyScheduledBaseline() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight revised = new ScheduleFlight(
                "PAX", 0L, null, "VJC30", null, "0000200",
                "HAK", "DAD", "0300", "0430", "G221",
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), null, null);
        ScheduleFlight baseline = new ScheduleFlight(
                "PAX", 288L, java.math.BigDecimal.valueOf(93), "VJC30", null, "0000200",
                "HAK", "HAN", "0300", "0430", "G221",
                LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 14), "PAX 333", null);
        SchedulePermit revision = new SchedulePermit(
                "LD-2869/8/2026VN.REV1", "LD 02869/CHK/2026", "2869",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 8, 17),
                "VJC", null, 24, "Vietjet", "NO",
                false, false, false, "REV1\nCác chi tiết khác không thay đổi",
                List.of(revised), List.of());
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(10L, 20L, "VJC",
                        "LD 02869/S/CHK/2026", List.of(baseline))));
        ProcessingContext context = context(revision, "LD_2869_RVS_1_VJC.docx");

        new RevisionReconciliationStep(gateway).reconcile(context);

        SchedulePermit reconciled = context.getSchedulePermit();
        assertThat(reconciled.atfmTargetPermitId()).isEqualTo("LD 02869/S/CHK/2026");
        assertThat(reconciled.usesScheduledStorage()).isTrue();
        assertThat(reconciled.seasonalPermit()).isFalse();
        assertThat(reconciled.flights().getFirst().craftId()).isEqualTo(288L);
    }

    @Test
    void reconcile_ld79Rvs7MaterializesOnlyDocumentedOldRowsAndIsIdempotent() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight old1 = ld79Flight(1, 11, "0200");
        ScheduleFlight old2 = ld79Flight(15, 23, "0200");
        ScheduleFlight new1 = ld79Flight(1, 11, "0150");
        ScheduleFlight new2 = ld79Flight(15, 23, "0150");
        SchedulePermit revision = new SchedulePermit(
                "LD-79/A/S/2026VN/REV7", "LD 0079A/S/CHK/2026", "79A",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 9, 23),
                "VAG", "LD-79/A/S/2026VN", 24, "Vietravel Airlines", "SC",
                false, false, false, "REV7\nOther details unchanged",
                List.of(new1, new2), List.of(old1, old2));
        // Existing base/revision data must enrich, never become additional OLD rows.
        when(gateway.findRevisionBaseline(revision)).thenReturn(java.util.Optional.of(
                new AtfmRevisionBaseline(1L, 2L, "VAG", List.of(old1, old2, new1, new2))));
        ProcessingContext context = context(revision, "LD-79-REV7.docx");
        var step = new RevisionReconciliationStep(gateway);
        step.reconcile(context);
        SchedulePermit once = context.getSchedulePermit();
        assertThat(once.atfmTargetPermitId()).isEqualTo("LD 0079AR7/S/CHK/2026");
        assertThat(once.flights()).hasSize(4);
        assertThat(once.flights()).extracting(ScheduleFlight::etd)
                .containsExactly("0200", "0200", "0150", "0150");
        assertThat(once.flights()).extracting(ScheduleFlight::beginDate).containsExactly(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
        step.reconcile(context);
        assertThat(context.getSchedulePermit()).isEqualTo(once);
        // A fresh retry parsed from Word also yields the same four rows.
        ProcessingContext retry = context(revision, "LD-79-REV7.docx");
        step.reconcile(retry);
        assertThat(retry.getSchedulePermit()).isEqualTo(once);
    }

    @Test
    void reconcile_keepsOldAndNewEvenWhenBusinessFieldsAreIdentical() {
        AtfmScheduleGateway gateway = mock(AtfmScheduleGateway.class);
        ScheduleFlight row = ld79Flight(1, 11, "0150");
        SchedulePermit revision = permit(List.of(row), List.of(row));
        ProcessingContext context = context(revision);
        new RevisionReconciliationStep(gateway).reconcile(context);
        assertThat(context.getSchedulePermit().flights()).hasSize(2);
        assertThat(context.getSchedulePermit().flights().getFirst().remark()).startsWith("HUY");
        assertThat(context.getSchedulePermit().flights().getLast().remark()).doesNotContain("HUY");
    }

    private ScheduleFlight ld79Flight(int firstDay, int lastDay, String departure) {
        return new ScheduleFlight("PAX", 1935L, java.math.BigDecimal.ZERO, "VAG672", null,
                "1004507", "VVTS", "VVDN", departure, "0325", "Q2",
                LocalDate.of(2026, 10, firstDay), LocalDate.of(2026, 10, lastDay), null, "A320");
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
