package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.atfm.AtfmAircraftTypeResolver;
import vatm.aerosync.worker.atfm.AtfmAirportCodeResolver;
import vatm.aerosync.worker.atfm.AtfmViaResolver;
import vatm.aerosync.worker.atfm.JdbcPermitOperatorResolver;
import vatm.aerosync.worker.atfm.JdbcAtfmScheduleGateway;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WordPermitCorpusRegressionTest {

    @Test
    void parseConfiguredPermitCorpus() throws Exception {
        String corpusDirectory = System.getProperty("permit.corpus.dir");
        String corpusManifest = System.getProperty("permit.corpus.manifest");
        Assumptions.assumeTrue(
                (corpusDirectory != null && !corpusDirectory.isBlank())
                        || (corpusManifest != null && !corpusManifest.isBlank()),
                "Set -Dpermit.corpus.dir or -Dpermit.corpus.manifest to validate Word permits");

        WordPermitDocumentReader reader = new WordPermitDocumentReader();
        WordPermitFormatDetector detector =
                new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        AtfmDatabaseProperties liveAtfm = liveAtfmProperties();
        PermitOperatorCatalog operatorCatalog = new PermitOperatorCatalog();
        DocxSchedulePermitParser parser = liveAtfm == null
                ? new DocxSchedulePermitParser(reader, detector)
                : new DocxSchedulePermitParser(
                        reader, detector, new AirportCodeCatalog(), operatorCatalog,
                        new JdbcPermitOperatorResolver(liveAtfm), Clock.systemDefaultZone());
        AircraftTypeResolutionStep aircraftTypeResolutionStep = liveAtfm == null
                ? null
                : new AircraftTypeResolutionStep(
                        new AircraftTypeCatalog(),
                        new AtfmAircraftTypeResolver(liveAtfm));
        RevisionReconciliationStep revisionReconciliationStep = liveAtfm == null
                ? null
                : new RevisionReconciliationStep(
                        new JdbcAtfmScheduleGateway(liveAtfm, new AtfmAirportCodeResolver()),
                        new AirportCodeCatalog());
        ViaResolutionStep viaResolutionStep = liveAtfm == null
                ? null
                : new ViaResolutionStep(
                        liveAtfm,
                        new AtfmAirportCodeResolver(),
                        new AtfmViaResolver());
        BusinessRuleValidatorStep validator = new BusinessRuleValidatorStep();
        Path reportDirectory = reportDirectory();

        List<Path> files = corpusManifest != null && !corpusManifest.isBlank()
                ? filesFromManifest(Path.of(corpusManifest))
                : filesFromDirectory(Path.of(corpusDirectory));
        assertThat(files)
                .as("Word permit files in configured corpus")
                .isNotEmpty();

        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> nonPermitDocuments = new ArrayList<>();
        for (Path file : files) {
            DocxPermitFormatProfile detectedProfile = null;
            String carrierIdentity = "IATA=-,ICAO=-";
            try {
                WordPermitDocument document = reader.read(file);
                carrierIdentity = carrierIdentity(document.rawContent());
                writeDocumentReport(reportDirectory, file, document);
                if (!containsPermitIdentity(document.rawContent())) {
                    nonPermitDocuments.add(file.getFileName().toString());
                    continue;
                }
                if (Pattern.compile("(?iu)(?:PASSENGER\\s+LIST|DANH\\s+SÁCH\\s+HÀNH\\s+KHÁCH|"
                                + "APPLICATION\\s+FORM|ĐƠN\\s+(?:XIN|ĐỀ NGHỊ))")
                        .matcher(document.rawContent()).find()
                        && !DocxSchedulePermitParser.looksLikeIssuedPermitDocument(document.rawContent())) {
                    nonPermitDocuments.add(file.getFileName().toString());
                    continue;
                }
                DocxPermitFormatProfile profile = detector.detect(document, file.getFileName().toString());
                detectedProfile = profile;
                SchedulePermit permit =
                        parser.parse(document, file.getFileName().toString());
                ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                        1L, file.toString(), "corpus", FileSourceType.FILESYSTEM, false));
                context.setSchedulePermit(permit);
                new NormalizerStep(java.time.ZoneId.systemDefault()).normalize(context);
                permit = context.getSchedulePermit();
                if (aircraftTypeResolutionStep == null) {
                    context.setSchedulePermit(permit.withFlights(
                            permit.flights().stream()
                                    .map(flight -> flight.withResolvedAircraft(1L, BigDecimal.ZERO))
                                    .toList()));
                } else {
                    revisionReconciliationStep.reconcile(context);
                    aircraftTypeResolutionStep.resolve(context);
                    viaResolutionStep.resolve(context);
                }
                validator.validate(context);
                successes.add("%s | %s | %s | %d flight(s) | %s".formatted(
                        file.getFileName(),
                        profile.id(),
                        permit.normalizedPermitId(),
                        permit.flights().size(),
                        permit.reviewOnly() ? "review-only" : "import-ready"));
            } catch (NonPermitDocumentException exception) {
                nonPermitDocuments.add(file.getFileName().toString());
            } catch (Exception exception) {
                failures.add("%s | %s | %s | %s".formatted(
                        file.getFileName(),
                        detectedProfile == null ? "<no-profile>" : detectedProfile.id(),
                        carrierIdentity,
                        rootMessage(exception)));
            }
        }

        System.out.println("WORD PERMIT CORPUS SUCCESSES (" + successes.size() + ")");
        successes.forEach(System.out::println);
        System.out.println("WORD PERMIT CORPUS FAILURES (" + failures.size() + ")");
        failures.forEach(System.out::println);
        System.out.println("WORD NON-PERMIT DOCUMENTS (" + nonPermitDocuments.size() + ")");
        nonPermitDocuments.forEach(System.out::println);

        assertThat(failures)
                .as("Every configured Word permit should match a profile and parse")
                .isEmpty();
    }

    @Test
    void readsUtf8BomManifest(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path document = directory.resolve("permit.docx");
        Files.write(document, new byte[0]);
        Path manifest = directory.resolve("manifest.txt");
        Files.writeString(manifest, "\uFEFF" + document + "\n# ignored comment\n", StandardCharsets.UTF_8);
        assertThat(filesFromManifest(manifest)).containsExactly(document);
    }

    private List<Path> filesFromManifest(Path manifest) throws Exception {
        assertThat(manifest).as("Configured permit corpus manifest").isRegularFile();
        List<Path> files = Files.readAllLines(manifest).stream()
                .map(line -> line.replaceFirst("^\\uFEFF", "").trim())
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(Path::of)
                .filter(this::isWordDocument)
                .filter(path -> !isTemporaryWordDocument(path))
                .toList();
        assertThat(files).as("Word permit paths in configured manifest").allMatch(Files::isRegularFile);
        return files;
    }

    private List<Path> filesFromDirectory(Path directory) throws Exception {
        assertThat(directory).as("Configured permit corpus directory").isDirectory();
        try (var paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(this::isWordDocument)
                    .filter(path -> !isTemporaryWordDocument(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private boolean containsPermitIdentity(String content) {
        return Pattern.compile(
                        "(?iu)\\b(?:LD|OF|O\\s*/\\s*F|QLB)\\s*[-:/ ]*\\d")
                .matcher(content)
                .find();
    }

    private String carrierIdentity(String content) {
        String folded = java.text.Normalizer.normalize(
                        content.replace('\u0110', 'D').replace('\u0111', 'd'),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        java.util.regex.Matcher iata = Pattern.compile(
                "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                        + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{2})(?![A-Z0-9])")
                .matcher(folded);
        java.util.regex.Matcher icao = Pattern.compile(
                "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                        + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{3})(?![A-Z0-9])")
                .matcher(folded);
        return "IATA=" + (iata.find() ? iata.group("value").toUpperCase(Locale.ROOT) : "-")
                + ",ICAO=" + (icao.find() ? icao.group("value").toUpperCase(Locale.ROOT) : "-");
    }

    private AtfmDatabaseProperties liveAtfmProperties() {
        if (!Boolean.getBoolean("permit.corpus.resolve-aircraft")) {
            return null;
        }
        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl(requiredEnvironment("APP_ATFM_DATASOURCE_URL"));
        properties.setUsername(requiredEnvironment("APP_ATFM_DATASOURCE_USERNAME"));
        properties.setPassword(requiredEnvironment("APP_ATFM_DATASOURCE_PASSWORD"));
        return properties;
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

    private boolean isTemporaryWordDocument(Path path) {
        return path.getFileName().toString().startsWith("~$");
    }

    private String rootMessage(Throwable throwable) {
        if (throwable instanceof BusinessRuleException businessRule
                && !businessRule.getRowErrors().isEmpty()) {
            return businessRule.getRowErrors().stream()
                    .map(error -> "%s[row=%d,value=%s]".formatted(
                            error.code(), error.rowNumber(), error.value()))
                    .distinct()
                    .toList()
                    .toString();
        }
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
        String sourceKey = source.toAbsolutePath().normalize().toString();
        String safeName = Integer.toUnsignedString(sourceKey.hashCode(), 16)
                + "-"
                + source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]+", "_");
        Files.writeString(
                reportDirectory.resolve(safeName + ".txt"),
                report,
                StandardCharsets.UTF_8);
    }
}
