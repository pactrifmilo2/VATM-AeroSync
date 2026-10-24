package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Of8034RevisionFlowTest {

    @Test
    void reportsRevisionStateForEachStoredVersion() throws Exception {
        String root = System.getProperty("permit.of8034.dir", "");
        Assumptions.assumeTrue(!root.isBlank(), "Set -Dpermit.of8034.dir to the OF-8034 version directory");

        String quarantineRoot = System.getProperty("permit.of8034.quarantine.dir", root);

        List<String> names = List.of(
                "local_20261024_152854_fs_duongvtt_20261024_103150_email_000_OF-8034-XKY814-815_ON_25-26OCT26_dht_c707da72_d0747c4c.docx",
                "local_20261024_153544_fs_duongvtt_20261024_103150_email_000_OF-8034-XKY814-815_ON_25-26OCT26_dht_c707da72_c348f108.docx",
                "local_20261024_154138_fs_quarantine_duongvtt_20261024_103150_email_000_OF-8034-XKY814-815_ON_25-26OCT26_dht_c707da72_d9d6ef17.docx");
        WordPermitDocumentReader reader = new WordPermitDocumentReader();
        WordPermitFormatDetector detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        DocxSchedulePermitParser parser = new DocxSchedulePermitParser();
        List<SchedulePermit> permits = new java.util.ArrayList<>();

        for (String name : names) {
            String selectedRoot = name.contains("_d9d6ef17") ? quarantineRoot : root;
            Path path = Path.of(selectedRoot).resolve(name);
            Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(path), "Missing sample: " + path);
            WordPermitDocument document = reader.read(path);
            DocxPermitFormatProfile profile = detector.detect(document, name);
            SchedulePermit permit = parser.parse(document, name);
            permits.add(permit);
            System.out.printf("%s | profile=%s | source=%s | normalized=%s | revision=%s | explicit=%s | implicitTable=%s | revisionNumber=%s | seasonal=%s | referencedSeasonNew=%s | createsNewPermit=%s | workflow=%s | flights=%d | originalFlights=%d%n",
                    name, profile.id(), permit.sourcePermitNumber(), permit.normalizedPermitId(),
                    permit.revision(), permit.explicitRevision(), permit.implicitTableRevision(),
                    permit.revisionNumber(), permit.seasonalPermit(), permit.referencedSeasonNewPermit(), permit.revisionCreatesNewPermit(), permit.workflow(), permit.flights().size(), permit.originalFlights().size());
        }

        assertThat(permits).hasSize(3);
        assertThat(permits.get(0).implicitTableRevision()).isFalse();
        assertThat(permits.get(1).implicitTableRevision()).isTrue();
        assertThat(permits.get(1).revisionNumber()).isNull();
        assertThat(permits.get(2).explicitRevision()).isTrue();
        assertThat(permits.get(2).revisionNumber()).isEqualTo(1);
    }
}
