package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermitReferenceCatalogTest {

    private final AirportCodeCatalog airports = new AirportCodeCatalog();
    private final AircraftTypeCatalog aircraft = new AircraftTypeCatalog();

    @Test
    void airportCatalog_normalizesIataAndPreservesIcao() {
        assertThat(airports.normalize(" HAN ")).isEqualTo("VVNB");
        assertThat(airports.normalize("SHA")).isEqualTo("ZSSS");
        assertThat(airports.normalize("VHHH")).isEqualTo("VHHH");
    }

    @Test
    void aircraftCatalog_prefersCompositeMappingThenFallsBackToKnownToken() {
        assertThat(aircraft.resolve("74Y/77F/76Y").craftId()).isEqualTo(1019L);
        assertThat(aircraft.resolve("unknown / GLF6").craftId()).isEqualTo(1712L);
        assertThat(aircraft.resolve("unmapped")).isNull();
    }
}
