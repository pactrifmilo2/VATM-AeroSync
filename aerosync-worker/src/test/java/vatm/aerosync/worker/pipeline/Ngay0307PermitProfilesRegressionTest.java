package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Ngay0307PermitProfilesRegressionTest {

    private final WordPermitDocumentReader reader = new WordPermitDocumentReader();
    private final WordPermitFormatDetector detector =
            new WordPermitFormatDetector(new DocxPermitProfileCatalog());
    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void everySampleUsesAnAirlineSpecificProfileAndReturnsCompleteCoreData() throws Exception {
        List<Path> files;
        try (var paths = Files.list(sampleDirectory())) {
            files = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        assertThat(files).hasSize(62);

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String profileId = detector.detect(reader.read(file), fileName).id();
            if ("OF 4881.docx".equals(fileName)) {
                assertThat(profileId).isEqualTo("mas-english-overflight-issued");
            }
            SchedulePermit permit = parser.parse(file, fileName);

            assertThat(profileId)
                    .as("profile for %s", fileName)
                    .doesNotStartWith("caav-generic");
            assertThat(Files.readString(profileDirectory().resolve(profileId + ".yaml")))
                    .as("standalone profile for %s", fileName)
                    .doesNotContain("extends:");
            assertThat(permit.operatorId())
                    .as("operator for %s", fileName)
                    .isEqualTo(expectedOperator(fileName));
            assertThat(permit.normalizedPermitId())
                    .as("permit id for %s", fileName)
                    .matches("^(?:LD|O/F) \\d{5}/S/CHK/20\\d{2}$");
            assertThat(permit.flights())
                    .as("flights for %s", fileName)
                    .isNotEmpty()
                    .allSatisfy(flight -> assertCompleteCoreFlight(fileName, permit, flight));
        }
    }

    @Test
    void revisionProfilesReadOnlyTheReplacementSchedule() {
        assertThat(parse("LD 2353 HAI rev2.docx").reviewOnly()).isFalse();
        assertFirst("LD 2473 KYE REV1.docx", "KYE9723", "RKSI", "2200", "VVNB");
        assertFirst("LD 2475 GTI (REV1).docx", "GTI8117", "KORD", "1215", "RKSI");
        assertFirst("LD 2486  N77999 (REV1).docx", "N77999", "VVTS", "0000", "VVPQ");
        assertFirst("OF 4669 RVS 2.docx", "T7999", "RJTT", "0315", "WSSL");
        assertFirst("of 4813 rvs 1.docx", "B1999", "VTBD", "0300", "RPLL");
        assertThat(parse("LD 2517 HVN.docx").flights()).hasSize(17);
    }

    @Test
    void inlineAndCarrierSpecificVariantsReturnTheirSourceValues() {
        SchedulePermit romcargo = parse("OF 4794_RCR3373.docx");
        assertThat(romcargo.normalizedPermitId()).isEqualTo("O/F 04794/S/CHK/2025");
        assertThat(romcargo.flights().getFirst().via()).isEqualTo("R474 W21 B214 TEBAK LADON");

        SchedulePermit vistajet = parse("OF 4795_VJT861.docx");
        assertThat(vistajet.normalizedPermitId()).isEqualTo("O/F 04795/S/CHK/2025");
        assertThat(vistajet.flights().getFirst().via()).isEqualTo("BUNTA A1 PAPRA");

        SchedulePermit turkmenistan = parse("LD 2511 TUA CORR.DOCX");
        assertThat(turkmenistan.flights()).hasSize(2);
        assertThat(turkmenistan.flights().getFirst().flightNumber()).isEqualTo("TUA693");

        SchedulePermit malaysia = parse("OF 4881.docx");
        assertThat(malaysia.flightType()).isEqualTo("SC");
        assertThat(malaysia.flights()).extracting(ScheduleFlight::sourceAircraftType)
                .containsOnly("A33X");

        SchedulePermit korean = parse("of 4880 KAL.docx");
        assertThat(korean.flights()).hasSize(2);
        assertThat(korean.flights().getFirst().via()).isEqualTo("N892 M765");
    }

    private void assertCompleteCoreFlight(String fileName,
                                          SchedulePermit permit,
                                          ScheduleFlight flight) {
        assertThat(flight.flightNumber()).as("flight number in %s", fileName).isNotBlank();
        assertThat(flight.fromAirport()).as("departure in %s", fileName).matches("[A-Z0-9]{3,4}");
        assertThat(flight.toAirport()).as("arrival in %s", fileName).matches("[A-Z0-9]{3,4}");
        assertThat(flight.etd()).as("ETD in %s", fileName).matches("\\d{4}");
        assertThat(flight.serviceDays()).as("service days in %s", fileName).matches("[0-7]{7}");
        assertThat(flight.beginDate()).as("begin date in %s", fileName).isNotNull();
        assertThat(flight.endDate()).as("end date in %s", fileName)
                .isAfterOrEqualTo(flight.beginDate());
        assertThat(flight.sourceAircraftType()).as("aircraft in %s", fileName).isNotBlank();
        assertThat(flight.purposeId()).as("purpose in %s", fileName).isNotBlank();
        if ("LD".equals(permit.permitType())) {
            assertThat(flight.eta()).as("ETA in %s", fileName).isNotBlank();
        }
    }

    private void assertFirst(String fileName,
                             String flightNumber,
                             String from,
                             String etd,
                             String to) {
        ScheduleFlight flight = parse(fileName).flights().getFirst();
        assertThat(flight.flightNumber()).isEqualTo(flightNumber);
        assertThat(flight.fromAirport()).isEqualTo(from);
        assertThat(flight.etd()).isEqualTo(etd);
        assertThat(flight.toAirport()).isEqualTo(to);
    }

    private SchedulePermit parse(String fileName) {
        Path file = sampleDirectory().resolve(fileName);
        return parser.parse(file, fileName);
    }

    private Path sampleDirectory() {
        String configuredDirectory = System.getProperty("permit.sample.directory");
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory).toAbsolutePath().normalize();
        }
        return Path.of("..", "ngay0307").toAbsolutePath().normalize();
    }

    private Path profileDirectory() {
        return Path.of(
                "src", "main", "resources", "permit-formats").toAbsolutePath().normalize();
    }

    private String expectedOperator(String fileName) {
        return switch (fileName) {
            case "LD  2501 IGO.docx" -> "IGO";
            case "LD 2353 HAI rev2.docx" -> "HAI";
            case "LD 2473 KYE REV1.docx", "LD 2473 KYE.doc" -> "KYE";
            case "LD 2475 GTI (REV1).docx", "OF 4878.docx" -> "GTI";
            case "LD 2502 BAV.docx" -> "BAV";
            case "LD 2503 MYU.docx" -> "MYU";
            case "LD 2504 SPA C26_06JUL-13JUL_ND.docx",
                    "LD 2505  SPA C26_04JUL-12JUL_FrIN-FrOut_VNA921.docx" -> "SPQ";
            case "LD 2510 SIN.docx" -> "SIN";
            case "LD 2511 TUA CORR.DOCX", "LD 2511 TUA.DOCX" -> "TUA";
            case "LD 2514 VNA268.doc" -> "VNA";
            case "LD 2515 SAV298.doc" -> "SAV";
            case "LD 2516 HKC327.doc" -> "HKC";
            case "LD 2517 HVN.docx" -> "HVN";
            case "OF 4690 RVS 1 VJT232.docx", "OF 4690 RVS 2.docx",
                    "OF 4795_VJT861.docx" -> "VJT";
            case "OF 4794_RCR3373.docx" -> "RCR";
            case "OF 4796_FDX9080.docx", "OF 4872 FDX.docx" -> "FDX";
            case "OF 4840 RVS 1.docx" -> "RED";
            case "OF 4869 TAY400.docx" -> "TAY";
            case "OF 4870 ABD4800.docx", "OF 4895_ABD3571.docx" -> "ABD";
            case "OF 4875.docx" -> "VPB";
            case "OF 4876.docx", "OF 4877.docx" -> "AIR";
            case "of 4880 KAL.docx" -> "KAL";
            case "OF 4881.docx" -> "MAS";
            case "OF 4887 N858KE.docx" -> "BLR";
            case "OF 4890.docx" -> "OGA";
            case "OF 4892 T7BOSS.docx" -> "ASI";
            case "OF 4894 HSDID.docx" -> "ACJ";
            case "OF 4896 AIQ9701.docx" -> "AIQ";
            case "REV1 LD 2467 AZG.docx" -> "AZG";
            case "LD 2486  N77999 (REV1).docx", "LD 2506 N65WL.doc",
                    "LD 2507  9MTMJ.docx", "LD 2508  N111AP.doc",
                    "LD 2509 MSCDM.doc", "LD 2512 N88AY.doc", "LD 2513 B652Q.doc",
                    "OF 4669 RVS 2.docx", "OF 4794_VQBOY.docx",
                    "OF 4795_B65AP.docx", "OF 4868 N111AP.docx", "OF 4871.docx",
                    "OF 4873 VPBFP.docx", "OF 4879.docx", "OF 4882.docx",
                    "OF 4883.docx", "OF 4884.docx", "OF 4885.docx", "OF 4886.docx",
                    "OF 4888.docx", "OF 4889.docx", "OF 4891.docx", "OF 4893.docx",
                    "of 4813 rvs 1.docx" -> "PRV";
            default -> throw new AssertionError("Missing expected operator for " + fileName);
        };
    }
}
