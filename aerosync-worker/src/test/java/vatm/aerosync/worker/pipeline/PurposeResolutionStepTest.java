package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.ScheduleFlight;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PurposeResolutionStepTest {

    @Test
    void resolvePurposeUsesTheLongestDictionarySynonymAndPreservesAircraftRemark() {
        ScheduleFlight flight = new ScheduleFlight(
                "PAX", 1L, null, "VN123", null, "1234567", "VVTS", "VHHH",
                "0100", "0300", null, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2), "PAX A321", "321");

        String purpose = PurposeResolutionStep.resolvePurpose(
                List.of(
                        new PurposeResolutionStep.PurposeEntry("PAX", "cho khach; transporting passengers"),
                        new PurposeResolutionStep.PurposeEntry("CAR", "cho hang; transporting cargo")),
                "Purpose of flight: transporting cargo",
                flight);

        assertThat(purpose).isEqualTo("CAR");
        assertThat(flight.withPurposeId(purpose).remark()).isEqualTo("PAX A321");
    }

    @Test
    void resolvePurposeFallsBackToParsedCodeWhenDictionaryHasNoMatch() {
        ScheduleFlight flight = new ScheduleFlight(
                "PAX", 1L, null, "VN124", null, "1234567", "VVTS", "VHHH",
                "0100", "0300", null, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2), "PAX A321", "321");

        assertThat(PurposeResolutionStep.resolvePurpose(
                List.of(new PurposeResolutionStep.PurposeEntry("CAR", "cho hang")),
                "Routine operation", flight)).isEqualTo("PAX");
    }

    @Test
    void ignoresAirlineNameAndAdministrativeOtherDetailsText() {
        ScheduleFlight flight = new ScheduleFlight(
                "CAR", 1L, null, "THY6242", null, "1234567", "VVTS", "VHHH",
                "0100", "0300", null, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2), "CAR A77W", "77X");

        assertThat(PurposeResolutionStep.resolvePurpose(
                List.of(
                        new PurposeResolutionStep.PurposeEntry("CAR", "cargo; transporting cargo"),
                        new PurposeResolutionStep.PurposeEntry("OTH", "other")),
                "Carrier: TURKISH AIRLINES CARGO\nOther details unchanged.",
                flight)).isEqualTo("CAR");
    }

    @Test
    void labelledPurposeWithSectionNumberAndParenthesizedPluralIsMapped() {
        ScheduleFlight flight = new ScheduleFlight("PAX", 1L, null, "SV123", null,
                "1234567", "OERK", "VHHH", "0100", "0300", null,
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), null);
        var entries = List.of(new PurposeResolutionStep.PurposeEntry("PAX", "cho khach; passenger"),
                new PurposeResolutionStep.PurposeEntry("CAR", "cho hang; cargo"));
        assertThat(PurposeResolutionStep.resolvePurpose(entries,
                "4. Purpose of Flight(s): cargo", flight)).isEqualTo("CAR");
        assertThat(PurposeResolutionStep.resolvePurpose(entries,
                "3. Mục đích chuyến bay: chở hàng", flight)).isEqualTo("CAR");
    }

    @Test
    void purposeOfChangedFlightDoesNotOverrideOtherFlights() {
        var entries = List.of(new PurposeResolutionStep.PurposeEntry("PAX", "passenger"),
                new PurposeResolutionStep.PurposeEntry("CAR", "cargo"));
        ScheduleFlight changed = new ScheduleFlight("PAX", 1L, null, "SV101", null,
                "1234567", "OERK", "VHHH", "0100", "0300", null,
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), null);
        ScheduleFlight unchanged = changed.withFlightNumber("SV102");
        String word = "SV101 | Purpose: cargo\nSV102 | Purpose: passenger";
        assertThat(PurposeResolutionStep.resolvePurpose(entries, word, changed)).isEqualTo("CAR");
        assertThat(PurposeResolutionStep.resolvePurpose(entries, word, unchanged)).isEqualTo("PAX");
    }
}
