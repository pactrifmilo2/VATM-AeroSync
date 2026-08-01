package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermitOperatorCatalogTest {

    private final PermitOperatorCatalog catalog = new PermitOperatorCatalog();

    @Test
    void normalizeFlightNumber_mapsVietnamAirlinesIataPrefixToIcao() {
        assertThat(catalog.normalizeFlightNumber("VN1822", "HVN"))
                .isEqualTo("HVN1822");
        assertThat(catalog.normalizeFlightNumber("VN7158", "HVN"))
                .isEqualTo("HVN7158");
    }

    @Test
    void normalizeFlightNumber_usesPermitOperatorToResolveAmbiguousIataCode() {
        assertThat(catalog.normalizeFlightNumber("3Q123", "KCH"))
                .isEqualTo("KCH123");
        assertThat(catalog.normalizeFlightNumber("3Q123", null))
                .isEqualTo("3Q123");
    }

    @Test
    void operatorForIata_resolvesOperatorWhenDocumentDoesNotContainIcao() {
        assertThat(catalog.operatorForIata("VJ")).isEqualTo("VJC");
        assertThat(catalog.operatorForIata("NN")).isNull();
        assertThat(catalog.operatorForIata("unknown")).isNull();
    }

    @Test
    void normalizeFlightNumber_mapsInvalidDatabaseIcaoCodesToPrivateOperator() {
        assertThat(catalog.normalizeFlightNumber("2G123", "PRV")).isEqualTo("PRV123");
        assertThat(catalog.normalizeFlightNumber("3K123", "PRV")).isEqualTo("PRV123");
        assertThat(catalog.normalizeFlightNumber("MG123", "PRV")).isEqualTo("PRV123");
        assertThat(catalog.normalizeFlightNumber("VF123", "PRV")).isEqualTo("PRV123");
        assertThat(catalog.normalizeFlightNumber("NN123", "PRV")).isEqualTo("PRV123");

        assertThat(catalog.normalizeFlightNumber("2G123", "CRG")).isEqualTo("CRG123");
        assertThat(catalog.normalizeFlightNumber("3K123", "JSA")).isEqualTo("JSA123");
    }

    @Test
    void normalizeFlightNumber_preservesIcaoAndNonFlightValues() {
        assertThat(catalog.normalizeFlightNumber("HVN1822", "HVN"))
                .isEqualTo("HVN1822");
        assertThat(catalog.normalizeFlightNumber("VN-B593", "VNB"))
                .isEqualTo("VNB593");
    }
}
