package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtfmPermitIdNormalizerTest {

    @Test
    void normalizeReference_mapsNumberedPermitToAtfmId() {
        assertThat(AtfmPermitIdNormalizer.normalizeReference("LD-2372/6/2026VN"))
                .contains("LD 02372/S/CHK/2026");
    }

    @Test
    void normalizeReference_mapsSeasonalPermitToAtfmId() {
        assertThat(AtfmPermitIdNormalizer.normalizeReference("LD-68/A/S/2026VN"))
                .contains("LD 0068A/S/CHK/2026");
    }

    @Test
    void normalizeReference_preservesNormalizedAtfmId() {
        assertThat(AtfmPermitIdNormalizer.normalizeReference("O/F 05199/S/CHK/2026"))
                .contains("O/F 05199/S/CHK/2026");
    }
}
