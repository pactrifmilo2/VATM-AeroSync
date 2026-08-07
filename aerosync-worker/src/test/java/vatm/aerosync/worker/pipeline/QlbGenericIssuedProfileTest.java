package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QlbGenericIssuedProfileTest {

    @Test
    void qlbPermit_usesAtfmAuthorCodeQlbInMasterAndNormalizedId() {
        DocxPermitFormatProfile profile = new DocxPermitProfileCatalog().profiles().stream()
                .filter(candidate -> "qlb-generic-issued".equals(candidate.id()))
                .findFirst()
                .orElseThrow();

        assertThat(profile.master().authorId()).isEqualTo("QLB");
        assertThat(profile.permit().normalizedTemplate())
                .isEqualTo("QLB {number}/S/QLB/{year}");
    }
}
