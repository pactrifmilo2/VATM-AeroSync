package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class BambooSeptemberAircraftTest {
    @Test
    void readsNumeric321FromRealSeptemberLandingPermit() throws Exception {
        Path folder = Path.of("D:/vatm-storage/quarantine/2026/09/24");
        Assumptions.assumeTrue(Files.isDirectory(folder));
        try (var files = Files.list(folder)) {
            Path file = files.filter(path -> path.getFileName().toString().contains("LD-3454")
                    && path.getFileName().toString().endsWith(".docx"))
                    .findFirst().orElse(null);
            Assumptions.assumeTrue(file != null);
            var permit = new DocxSchedulePermitParser().parse(file, file.getFileName().toString());
            assertThat(permit.flights()).hasSize(2)
                    .allSatisfy(flight -> assertThat(AircraftTypeNotation.normalize(
                            flight.sourceAircraftType())).isEqualTo("A321"));
        }
    }
}
