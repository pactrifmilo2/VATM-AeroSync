package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmAircraftTypeResolver;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WordPermitCorpusRegressionTest {

    @Test
    void parseConfiguredPermitCorpus() throws Exception {
        String corpusDirectory = System.getProperty("permit.corpus.dir");
        Assumptions.assumeTrue(corpusDirectory != null && !corpusDirectory.isBlank(),
                "Set -Dpermit.corpus.dir to validate a directory of Word permits");

        Path directory = Path.of(corpusDirectory);
        Assumptions.assumeTrue(Files.isDirectory(directory),
                "Configured permit corpus directory does not exist");

        WordPermitDocumentReader reader = new WordPermitDocumentReader();
        WordPermitFormatDetector detector =
                new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        DocxSchedulePermitParser parser =
                new DocxSchedulePermitParser(reader, detector);
        AircraftTypeResolutionStep aircraftTypeResolutionStep =
                liveAircraftTypeResolutionStep();
        BusinessRuleValidatorStep validator = new BusinessRuleValidatorStep();
        Path reportDirectory = reportDirectory();

        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isWordDocument)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertThat(files)
                .as("Word permit files in configured corpus")
                .isNotEmpty();

        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            try {
                WordPermitDocument document = reader.read(file);
                writeDocumentReport(reportDirectory, file, document);
                DocxPermitFormatProfile profile =
                        detector.detect(document, file.getFileName().toString());
                SchedulePermit permit =
                        parser.parse(document, file.getFileName().toString());
                ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                        1L, file.toString(), "corpus", FileSourceType.FILESYSTEM, false));
                context.setSchedulePermit(permit);
                if (aircraftTypeResolutionStep == null) {
                    context.setSchedulePermit(permit.withFlights(
                            permit.flights().stream()
                                    .map(flight -> flight.withResolvedAircraft(1L, BigDecimal.ZERO))
                                    .toList()));
                } else {
                    aircraftTypeResolutionStep.resolve(context);
                }
                validator.validate(context);
                successes.add("%s | %s | %s | %d flight(s) | %s".formatted(
                        file.getFileName(),
                        profile.id(),
                        permit.normalizedPermitId(),
                        permit.flights().size(),
                        permit.reviewOnly() ? "review-only" : "import-ready"));
            } catch (Exception exception) {
                failures.add("%s | %s".formatted(
                        file.getFileName(), rootMessage(exception)));
            }
        }

        System.out.println("WORD PERMIT CORPUS SUCCESSES (" + successes.size() + ")");
        successes.forEach(System.out::println);
        System.out.println("WORD PERMIT CORPUS FAILURES (" + failures.size() + ")");
        failures.forEach(System.out::println);

        assertThat(failures)
                .as("Every configured Word permit should match a profile and parse")
                .isEmpty();
    }

    private AircraftTypeResolutionStep liveAircraftTypeResolutionStep() {
        if (!Boolean.getBoolean("permit.corpus.resolve-aircraft")) {
            return null;
        }
        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl(requiredEnvironment("APP_ATFM_DATASOURCE_URL"));
        properties.setUsername(requiredEnvironment("APP_ATFM_DATASOURCE_USERNAME"));
        properties.setPassword(requiredEnvironment("APP_ATFM_DATASOURCE_PASSWORD"));
        return new AircraftTypeResolutionStep(
                new AircraftTypeCatalog(),
                new AtfmAircraftTypeResolver(properties));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be set when -Dpermit.corpus.resolve-aircraft=true");
        }
        return value;
    }

    private boolean isWordDocument(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private Path reportDirectory() throws Exception {
        String configured = System.getProperty("permit.corpus.report.dir");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path directory = Path.of(configured);
        Files.createDirectories(directory);
        return directory;
    }

    private void writeDocumentReport(Path reportDirectory,
                                     Path source,
                                     WordPermitDocument document) throws Exception {
        if (reportDirectory == null) {
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("FILE: ").append(source.getFileName()).append('\n');
        report.append("\nPARAGRAPHS\n").append(document.paragraphText()).append('\n');
        for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
            report.append("\nTABLE ").append(tableIndex + 1).append('\n');
            String context = document.tableContexts().get(tableIndex);
            if (!context.isBlank()) {
                report.append("CONTEXT: ").append(context).append('\n');
            }
            for (List<String> row : document.tables().get(tableIndex)) {
                report.append(String.join(" | ", row)).append('\n');
            }
        }
        String safeName = source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]+", "_");
        Files.writeString(
                reportDirectory.resolve(safeName + ".txt"),
                report,
                StandardCharsets.UTF_8);
    }
}
