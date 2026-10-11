package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class October08And09PermitProfilesTest {
    @Test
    void matchesScopedProfilesWithoutCrossingCarriers() throws Exception {
        String files = System.getProperty("permit.corpus.dir", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!files.isBlank(),
                "Set -Dpermit.corpus.dir to test actual Word samples");
        var reader = new WordPermitDocumentReader();
        var detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        var parser = new DocxSchedulePermitParser();
        Map<String, String> expected = Map.of(
                "TAX638", "tax-caav-seasonal-overflight-issued",
                "DHX-DHT", "dhx-caav-seasonal-overflight-issued",
                "HSCPH", "prv-siam-land-overflight-issued",
                "LD_16_A_W", "pta-caav-seasonal-landing-issued",
                "LD_13_A_W", "ces-caav-seasonal-landing-issued",
                "OF-57_A_W", "qtr-caav-seasonal-overflight-issued",
                "LD-3682", "qqe-english-landing-issued-review",
                "LD-3602", "prv-sinojet-landing-revision");
        int checked = 0;
        try (var paths = java.nio.file.Files.walk(Path.of(files))) {
            for (Path path : paths.filter(java.nio.file.Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().matches("(?i).*\\.docx?"))
                    .toList()) {
                var name = path.getFileName().toString();
                var key = expected.keySet().stream().filter(name::contains).findFirst();
                if (key.isEmpty() || name.startsWith("~$")) continue;
                var doc = reader.read(path);
                assertThat(detector.detect(doc, name).id()).as(name).isEqualTo(expected.get(key.get()));
                var permit = parser.parse(doc, name);
                assertThat(permit.flights()).as(name).isNotEmpty();
                assertThat(permit.reviewOnly()).as(name).isTrue();
                checked++;
            }
        }
        assertThat(checked).as("matching permit samples in %s", files).isPositive();
    }
}
