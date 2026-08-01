package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PermitReferenceCatalogTest {

    private final AirportCodeCatalog airports = new AirportCodeCatalog();
    private final AircraftTypeCatalog aircraft = new AircraftTypeCatalog();

    @Test
    void airportCatalog_normalizesIataAndPreservesIcao() {
        assertThat(airports.normalize(" HAN ")).isEqualTo("VVNB");
        assertThat(airports.normalize("SHA")).isEqualTo("ZSSS");
        assertThat(airports.normalize("DRW")).isEqualTo("YPDN");
        assertThat(airports.normalize("SRG")).isEqualTo("WARS");
        assertThat(airports.normalize("VHHH")).isEqualTo("VHHH");
    }

    @Test
    void aircraftCatalog_returnsDatabaseCodesWithoutIdsOrMtow() {
        assertThat(aircraft.candidates("A330-200F B777-200FB747-400F"))
                .containsExactly("A33X");
        assertThat(aircraft.candidates("74Y/77F/76Y"))
                .containsExactly("B74Y");
        assertThat(aircraft.candidates("unknown / GLF6"))
                .containsExactly("unknown", "GLF6");
        assertThat(aircraft.candidates("B787-900"))
                .containsExactly("B789");
        assertThat(aircraft.candidates("unmapped"))
                .containsExactly("unmapped");
    }

    @ParameterizedTest
    @MethodSource("observedAircraftAliases")
    void aircraftCatalog_normalizesObservedUnsupportedValues(
            String source,
            String expectedCandidate) {
        assertThat(aircraft.candidates(source)).containsExactly(expectedCandidate);
    }

    private static Stream<Arguments> observedAircraftAliases() {
        return Stream.of(
                Arguments.of("74Y", "B74Y"),
                Arguments.of("734", "B734"),
                Arguments.of("77W", "B77W"),
                Arguments.of("A319-115/A319-153N", "A319"),
                Arguments.of("A330-200F B777-200FB747-400F", "A33X"),
                Arguments.of("B767-300F", "767F"),
                Arguments.of("B787-800", "B788"),
                Arguments.of("B787-900", "B789"),
                Arguments.of("BOEING 738 OR SUBS B733F / SUBS B734F", "B738"),
                Arguments.of("BOEING 747-400F", "B74F"),
                Arguments.of("32X", "A32X"),
                Arguments.of("73Y/73P/73K", "73Y"),
                Arguments.of("B747-400", "B744"),
                Arguments.of("73W", "B737"),
                Arguments.of("747-400F/747-8F/777-200F", "B74Y"),
                Arguments.of("GULFSTREAM G450", "GLF4"),
                Arguments.of("GULFSTREAM GVII-G500", "G500"),
                Arguments.of("A319-115 OR SUB", "A319"),
                Arguments.of("B747F OR SUB", "B74F"),
                Arguments.of("BOMBARDIER CHALLENGER 604", "CL60"),
                Arguments.of("75W/76W", "75W"));
    }

    @Test
    void aircraftCatalog_keepsH25BAsDatabaseCandidate() {
        assertThat(aircraft.candidates("H25B")).containsExactly("H25B");
    }
}
