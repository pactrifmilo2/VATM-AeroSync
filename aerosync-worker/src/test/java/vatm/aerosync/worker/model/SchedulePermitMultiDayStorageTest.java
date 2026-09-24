package vatm.aerosync.worker.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulePermitMultiDayStorageTest {

    @Test
    void singleDayNonScheduledPermit_keepsNoStorageAndProfileSeason() {
        SchedulePermit permit = nonScheduledPermit(List.of(
                flight("VJ405", date(2026, 9, 11), date(2026, 9, 11)),
                flight("VJ406", date(2026, 9, 12), date(2026, 9, 12))));

        assertThat(permit.multiDayNonScheduled()).isFalse();
        assertThat(permit.usesScheduledStorage()).isFalse();
        assertThat(permit.flightType()).isEqualTo("NO");
        assertThat(permit.season()).isEqualTo("S");
    }

    @Test
    void anyDateRangeFlight_routesWholePermitToScheduledStorageKeepingFlightTypeNo() {
        SchedulePermit permit = nonScheduledPermit(List.of(
                flight("VJ405", date(2026, 9, 11), date(2026, 9, 30)),
                flight("VJ1813", date(2026, 9, 12), date(2026, 9, 12))));

        assertThat(permit.multiDayNonScheduled()).isTrue();
        assertThat(permit.usesScheduledStorage()).isTrue();
        assertThat(permit.flightType()).isEqualTo("NO");
        assertThat(permit.season()).isEqualTo("S");
    }

    @Test
    void nullEndDate_isTreatedAsSingleDay() {
        SchedulePermit permit = nonScheduledPermit(List.of(
                flight("VJ405", date(2026, 9, 11), null)));

        assertThat(permit.multiDayNonScheduled()).isFalse();
        assertThat(permit.usesScheduledStorage()).isFalse();
    }

    @Test
    void derivedSeason_followsIataSeasonOfEarliestFlightDate() {
        assertThat(seasonFor(date(2026, 12, 15), date(2026, 12, 20))).isEqualTo("W");
        assertThat(seasonFor(date(2026, 1, 10), date(2026, 2, 1))).isEqualTo("W");
        assertThat(seasonFor(date(2026, 6, 1), date(2026, 6, 15))).isEqualTo("S");
        // Last Sunday of March 2026 is the 29th; of October 2026 the 25th.
        assertThat(seasonFor(date(2026, 3, 20), date(2026, 4, 5))).isEqualTo("W");
        assertThat(seasonFor(date(2026, 3, 29), date(2026, 4, 5))).isEqualTo("S");
        assertThat(seasonFor(date(2026, 10, 20), date(2026, 10, 24))).isEqualTo("S");
        assertThat(seasonFor(date(2026, 10, 25), date(2026, 11, 2))).isEqualTo("W");
    }

    @Test
    void derivedSeason_usesEarliestBeginDateAcrossAllFlights() {
        SchedulePermit permit = nonScheduledPermit(List.of(
                flight("VJ405", date(2026, 11, 3), date(2026, 11, 10)),
                flight("VJ406", date(2026, 12, 20), date(2026, 12, 20))));

        assertThat(permit.season()).isEqualTo("W");
    }

    @Test
    void seasonalPermit_neverUsesDerivedSeason() {
        SchedulePermit permit = new SchedulePermit(
                "LD-01/A/W/2026VN", "LD 0001A/W/CHK/2026", "01A", "CHK", "LD", "A", "W",
                date(2026, 8, 12), "HVN", null, 24, "Vietnam Airlines",
                "SC", false, false, false, "",
                List.of(flight("VN123", date(2026, 6, 1), date(2026, 6, 30))), List.of());

        assertThat(permit.multiDayNonScheduled()).isFalse();
        assertThat(permit.flightType()).isEqualTo("SC");
        assertThat(permit.season()).isEqualTo("W");
    }

    @Test
    void existingScheduledTarget_withoutSeasonMarker_keepsDerivedSeason() {
        SchedulePermit permit = nonScheduledPermit(List.of(
                flight("VJ405", date(2026, 9, 11), date(2026, 9, 30))));

        SchedulePermit retargeted = permit.withExistingScheduledTarget("LD 03248/CHK/2026");

        assertThat(retargeted.existingScheduledRevision()).isTrue();
        assertThat(retargeted.usesScheduledStorage()).isTrue();
        assertThat(retargeted.atfmTargetPermitId()).isEqualTo("LD 03248/CHK/2026");
        assertThat(retargeted.season()).isEqualTo("S");
        assertThat(retargeted.flightType()).isEqualTo("NO");
    }

    @Test
    void existingScheduledTarget_withSeasonMarker_stillReadsSeasonFromId() {
        SchedulePermit permit = nonScheduledPermit(List.of(
                flight("VJ405", date(2026, 12, 11), date(2026, 12, 30))));

        SchedulePermit retargeted = permit.withExistingScheduledTarget("LD 0068A/S/CHK/2026");

        assertThat(retargeted.season()).isEqualTo("S");
    }

    private String seasonFor(LocalDate begin, LocalDate end) {
        return nonScheduledPermit(List.of(flight("VJ405", begin, end))).season();
    }

    private SchedulePermit nonScheduledPermit(List<ScheduleFlight> flights) {
        return new SchedulePermit(
                "LD-3248/9/2026VN", "LD 03248/CHK/2026", "3248", "CHK", "LD", "A", "S",
                date(2026, 9, 8), "VJC", null, 24, "302/3 Pho Kim Ma, Ha Noi",
                "NO", false, false, false, "", flights, List.of());
    }

    private ScheduleFlight flight(String flightNumber, LocalDate beginDate, LocalDate endDate) {
        return new ScheduleFlight(
                "PAX", 1L, BigDecimal.TEN, flightNumber, "VN-A001", "1234567",
                "HAN", "DLI", "0840", "1030", "Q1 W7", beginDate, endDate, null);
    }

    private LocalDate date(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
