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
        assertThat(flight.withPurposeId(purpose).remark()).isEqualTo("CAR A321");
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
}
