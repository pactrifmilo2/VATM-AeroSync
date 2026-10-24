package vatm.aerosync.worker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AirportFallbackMessageTest {

    @Test
    void explainsAirportFallbackWithSourceAndPlaceholderCode() {
        assertThat(VietnameseStoredErrorMessage.translate(
                "Schedule row 3: Airport code not found in ATFM: XXX; fallback VVVV applied"))
                .contains("XXX", "VVVV");
    }
}
