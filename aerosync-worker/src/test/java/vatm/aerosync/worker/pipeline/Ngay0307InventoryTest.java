package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

class Ngay0307InventoryTest {

    @Test
    void inventoryCurrentProfileCoverage() throws Exception {
        Path directory = Path.of("..", "ngay0307").toAbsolutePath().normalize();
        WordPermitDocumentReader reader = new WordPermitDocumentReader();
        WordPermitFormatDetector detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                try {
                    WordPermitDocument document = reader.read(file);
                    String operatorTable = document.tables().isEmpty()
                            ? "<none>"
                            : document.tables().getFirst().stream()
                                    .map(row -> String.join(" | ", row))
                                    .collect(java.util.stream.Collectors.joining(" || "));
                    System.out.printf("DOC|%s|%s%n", file.getFileName(), operatorTable);
                    String profile = detector.detect(document, file.getFileName().toString()).id();
                    SchedulePermit permit = parser.parse(file, file.getFileName().toString());
                    ScheduleFlight first = permit.flights().getFirst();
                    System.out.printf(
                            "OK|%s|%s|%s|%s|%d|%s|%s|%s|%s|%s|%s%n",
                            file.getFileName(), profile, permit.operatorId(),
                            permit.normalizedPermitId(), permit.flights().size(),
                            first.flightNumber(), first.fromAirport(), first.etd(),
                            first.toAirport(), first.eta(), first.sourceAircraftType());
                } catch (Exception exception) {
                    System.out.printf(
                            "ERROR|%s|%s%n",
                            file.getFileName(),
                            exception.getMessage().replaceAll("[\\r\\n]+", " "));
                }
            }
        }
    }
}
