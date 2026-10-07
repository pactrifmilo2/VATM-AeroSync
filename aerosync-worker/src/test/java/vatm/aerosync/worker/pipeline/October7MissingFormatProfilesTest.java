package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class October7MissingFormatProfilesTest {

    @Test
    void readsArlAndGnjSamples() throws Exception {
        String configured = System.getProperty("permit.october7.profile.files");
        Assumptions.assumeTrue(configured != null,
                "Set -Dpermit.october7.profile.files to audit ARL/GNJ samples");

        Map<String, Expected> expected = new LinkedHashMap<>();
        expected.put("email_1773999849_31409_001_LD-3663_GNJ68_07OCT26.docx",
                new Expected("gnj-english-landing-issued", "GNJ", "LD 03663/CHK/2026", 2, false));
        expected.put("haanhlv_20261007_094044_email_001_LD-3663_GNJ68_07OCT26_7513e573.docx",
                new Expected("gnj-english-landing-issued", "GNJ", "LD 03663/CHK/2026", 2, false));
        expected.put("email_1773999849_31431_001_LD-3659__REV1_.docx",
                new Expected("arl-english-landing-revision", "ARL", "LD 03659/CHK/2026", 2, true));
        expected.put("haanhlv_20261007_102048_email_001_LD-3659__REV1__d3641c73.docx",
                new Expected("arl-english-landing-revision", "ARL", "LD 03659/CHK/2026", 2, true));

        var reader = new WordPermitDocumentReader();
        var detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        var parser = new DocxSchedulePermitParser();
        for (String pathText : configured.split("\\|")) {
            Path path = Path.of(pathText);
            Expected target = expected.get(path.getFileName().toString());
            assertThat(target).as(path.toString()).isNotNull();
            var document = reader.read(path);
            var profile = detector.detect(document, path.getFileName().toString());
            assertThat(profile.id())
                    .as(path.toString()).isEqualTo(target.profile());
            var permit = parser.parse(document, path.getFileName().toString());
            assertThat(permit.operatorId()).isEqualTo(target.operator());
            assertThat(permit.normalizedPermitId()).isEqualTo(target.permit());
            assertThat(permit.flights()).hasSize(target.flights());
            if (target.revision()) {
                assertThat(permit.originalFlights()).hasSize(2);
            }
        }
    }

    private record Expected(String profile, String operator, String permit,
                            int flights, boolean revision) {
    }
}
