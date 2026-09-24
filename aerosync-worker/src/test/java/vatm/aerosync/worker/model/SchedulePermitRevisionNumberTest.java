package vatm.aerosync.worker.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulePermitRevisionNumberTest {

    @Test
    void recognizesVietnameseOtherDetailsUnchangedPhrase() {
        SchedulePermit permit = permit(
                "LD-32/C/S/2026VN/REV17", "LD 0032C/S/CHK/2026", "32C",
                "CÁC CHI TIẾT KHÁC KHÔNG THAY ĐỔI");

        assertThat(permit.otherDetailsUnchanged()).isTrue();
    }

    @Test
    void seasonalRevision_createsIndependentRNumberAndLookupKey() {
        SchedulePermit permit = permit(
                "LD-01/A/S/2026VN /REV2",
                "LD 0001A/S/CHK/2026",
                "01",
                "Other details unchanged");

        assertThat(permit.seasonalPermit()).isTrue();
        assertThat(permit.revisionNumber()).isEqualTo(2);
        assertThat(permit.importRevision()).isTrue();
        assertThat(permit.revisionPermitId()).isEqualTo("LD 0001AR2/S/CHK/2026");
        assertThat(permit.permitLookupKey()).isEqualTo("LD-01AR2");
        assertThat(permit.asSeasonalRevision(List.of()).permitNumber()).isEqualTo("01AR2");
    }

    @Test
    void overflightSeasonalRevision_ignoresSlashInsideOfPrefix() {
        SchedulePermit permit = permit(
                "O/F-01/A/S/2026VN /REV2",
                "O/F 0001A/S/CHK/2026",
                "01A",
                "REV2\nNEW SCHEDULE");

        assertThat(permit.revisionPermitId()).isEqualTo("O/F 0001AR2/S/CHK/2026");
        assertThat(permit.permitLookupKey()).isEqualTo("O/F-01AR2");
        assertThat(permit.asSeasonalRevision(List.of()).sourcePermitNumber())
                .isEqualTo("O/F-01AR2/A/S/2026VN /REV2");
        assertThat(permit.asSeasonalRevision(List.of()).permitNumber()).isEqualTo("01AR2");
        assertThat(permit.asSeasonalRevision(List.of()).atfmTargetPermitId())
                .isEqualTo("O/F 0001AR2/S/CHK/2026");
        assertThat(permit.flightType()).isEqualTo("SC");
    }

    @Test
    void flightType_isDerivedOnlyFromPermitIdentity() {
        assertThat(permit("LD-01/A/W/2026VN", "LD 0001A/W/CHK/2026", "01A", "")
                .flightType()).isEqualTo("SC");
        assertThat(permit("LD-2576/8/2026VN", "LD 02576/CHK/2026", "2576", "")
                .flightType()).isEqualTo("NO");
        assertThat(permit("LD-2953/08/2026VN", "LD 02953/S/CHK/2026", "2953", "")
                .flightType()).isEqualTo("NO");
        assertThat(permit("LD-2953/08/2026VN", "LD 02953/S/CHK/2026", "2953", "")
                .seasonalPermit()).isFalse();
    }

    @Test
    void originalAndNonScheduledPermits_useTextBeforeFirstSlashAsLookupKey() {
        assertThat(permit("LD-01/A/S/2026VN", "LD 0001A/S/CHK/2026", "01", "")
                .permitLookupKey()).isEqualTo("LD-01");
        assertThat(permit("LD-2576/8/2026VN", "LD-2576/08/2026", "2576", "")
                .permitLookupKey()).isEqualTo("LD-2576");
        assertThat(permit("O/F-2576/8/2026VN", "O/F-2576/08/2026", "2576", "")
                .permitLookupKey()).isEqualTo("O/F-2576");
    }

    @Test
    void nonScheduledRevision_revMarkerIsSufficientWithoutReplacementTable() {
        SchedulePermit withoutNewTable = permit(
                "LD-2576/8/2026VN /REV1", "LD-2576/08/2026", "2576", "REV1");
        SchedulePermit withVietnameseTable = permit(
                "LD-2576/8/2026VN /REV1", "LD-2576/08/2026", "2576",
                "REV1\nBẢNG LỊCH BAY SỬA ĐỔI");

        assertThat(withoutNewTable.importRevision()).isTrue();
        assertThat(withVietnameseTable.importRevision()).isTrue();
    }

    @Test
    void seasonalRevision_bareMarkerRequiresInferredNumber() {
        SchedulePermit permit = permit(
                "LD-01/A/S/2026VN /RVS", "LD 0001A/S/CHK/2026", "01A",
                "OTHER DETAILS REMAIN UNCHANGED");

        assertThat(permit.implicitTableRevision()).isTrue();

        SchedulePermit numbered = permit.withRevisionNumber(3);
        assertThat(numbered.importRevision()).isTrue();
        assertThat(numbered.revisionPermitId()).isEqualTo("LD 0001AR3/S/CHK/2026");
        assertThat(numbered.atfmTargetPermitId()).isEqualTo("LD 0001AR3/S/CHK/2026");
    }

    @Test
    void nonScheduledReplacementTable_withoutRevMarker_isStillARevision() {
        SchedulePermit permit = permit(
                "LD-2576/8/2026VN", "LD-2576/08/2026", "2576",
                "NEW FLIGHT SCHEDULE");

        assertThat(permit.implicitTableRevision()).isTrue();
    }

    @Test
    void bilingualRevisionContent_isRecognizedBySharedBusinessLogic() {
        SchedulePermit vietnamese = permit(
                "LD-2576/8/2026VN /REV1", "LD-2576/08/2026", "2576",
                "REV1\nB\u1EA2NG L\u1ECACH BAY S\u1EECa \u0110\u1ED4I\n"
                        + "C\u00E1c th\u00F4ng tin kh\u00E1c kh\u00F4ng thay \u0111\u1ED5i");
        SchedulePermit english = permit(
                "LD-2576/8/2026VN /RVS1", "LD-2576/08/2026", "2576",
                "RVS1\nREVISED FLIGHT SCHEDULE\nOther information remains unchanged");

        assertThat(vietnamese.importRevision()).isTrue();
        assertThat(vietnamese.otherDetailsUnchanged()).isTrue();
        assertThat(english.importRevision()).isTrue();
        assertThat(english.otherDetailsUnchanged()).isTrue();
    }

    @Test
    void mainDetailsIsUnchanged_isRecognized() {
        SchedulePermit permit = permit(
                "LD-2576/8/2026VN /RVS1", "LD-2576/08/2026", "2576",
                "RVS1\nOther main details is unchanged");

        assertThat(permit.otherDetailsUnchanged()).isTrue();
    }

    @Test
    void nonScheduledRevision_cannotCreateIndependentRPermit() {
        SchedulePermit permit = permit(
                "LD-2797/07/2026VN/REV1", "LD 02797/CHK/2026", "2797",
                "REV1\nNEW SCHEDULE");

        assertThatThrownBy(() -> new SchedulePermit(
                permit.sourcePermitNumber(), permit.normalizedPermitId(), permit.permitNumber(),
                permit.authorId(), permit.permitType(), permit.version(), permit.season(),
                permit.permitDate(), permit.operatorId(), permit.reference(), permit.validHours(),
                permit.billingAddress(), permit.flightType(), permit.iataAirportsAllowed(),
                permit.emptyAirwaysAllowed(), permit.reviewOnly(), permit.rawContent(),
                permit.flights(), permit.originalFlights(), permit.normalizedPermitId(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only a seasonal permit");
    }

    private SchedulePermit permit(String source, String normalized, String number, String raw) {
        return new SchedulePermit(
                source, normalized, number, "CHK", "LD", "A", "S",
                LocalDate.of(2026, 8, 12), "HVN", null, 24, "Vietnam Airlines",
                "SC", false, false, false, raw, List.of(), List.of());
    }
}
