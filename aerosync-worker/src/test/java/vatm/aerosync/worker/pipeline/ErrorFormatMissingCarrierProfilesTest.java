package vatm.aerosync.worker.pipeline;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorFormatMissingCarrierProfilesTest {
    @TempDir
    Path tempDir;

    private final WordPermitFormatDetector detector =
            new WordPermitFormatDetector(new DocxPermitProfileCatalog());
    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parsesArchivedSamplesWhenConfigured() throws Exception {
        String configured = System.getProperty("permit.missing-carriers.dir");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null,
                "Set -Dpermit.missing-carriers.dir to check the archived source documents");
        List<Path> files;
        try (var paths = Files.walk(Path.of(configured))) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        String[][] samples = {
                {"OF-14.A.S.2024VN_C8_ICV", "ICV", "23"},
                {"OF-08.B.W.2024VN-AIC-PAX", "AIC", "2"},
                {"OF-11A-S24-CEB", "CEB", "14"},
                {"OF_07.A.S.2024VN.AHK", "AHK", "12"},
                {"OF_07.A.W.2024VN.AHK", "AHK", "12"},
                {"OF-107_C_W_2024VN_CSZ", "CSZ", "2"},
                {"GW-OF-18_A_S_2024_GFA", "GFA", "4"},
                {"OF_09.A.S.2024VN_ALK", "ALK", "18"},
                {"OF-51_B_W_2024VN_JJA", "JJA", "2"},
                {"OF-91_A_S_2024VN_JJA", "JJA", "12"},
                {"thanhntm_20260812_193156_email_000_OF-1176", "LAO", "12"},
                {"thanhntm_20260812_193824_email_000_OF-7493", "LAO", "32"},
                {"duongvtt_20260812_183829_email_000_corrOF-7685", "FFM", "1"},
                {"duongvtt_20260812_183834_email_000_OF-7685", "FFM", "1"},
                {"OF-786.02.2024VN_UAL", "UAL", "2"},
                {"OF-15.A.W.2026VN.MPH", "MPH", "14"},
                {"OF-17.A.W.2026VN.SBI", "SBI", "2"},
                {"rvs1-OF-7415.12.2024vn-TNU812", "TNU", "2"},
                {"rvs1-OF-7414.12.2024vn-TNU812", "TNU", "2"},
                {"RVS1-OF-7410.12.2024VN", "TNU", "2"}
        };
        for (String[] sample : samples) {
            Path file = files.stream().filter(path -> {
                String name = path.getFileName().toString();
                return name.contains(sample[0]) && name.matches("(?i).*\\.docx?");
            }).findFirst().orElseThrow(() -> new AssertionError("Missing sample: " + sample[0]));
            var document = new WordPermitDocumentReader().read(file);
            assertThat(detector.detect(document, file.getFileName().toString()).id())
                    .as(sample[0]).startsWith(sample[1].toLowerCase(java.util.Locale.ROOT) + "-");
            var permit = parser.parse(file, file.getFileName().toString());
            assertThat(permit.operatorId()).as(sample[0]).isEqualTo(sample[1]);
            assertThat(permit.flights()).as(sample[0]).hasSize(Integer.parseInt(sample[2]));
            if (sample[1].equals("TNU")) {
                assertThat(permit.originalFlights()).as(sample[0]).hasSize(2);
                assertThat(permit.flights()).extracting(f -> f.flightNumber())
                        .containsExactly("TNU812", "TNU813");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ICV", "AIC", "CEB", "AHK", "CSZ", "GFA", "ALK", "JJA"})
    void seasonalCarrierWinsOverGenericAndRequiresIssuedIdentity(String carrier) {
        String text = "OVERFLIGHT PERMIT - WINTER 2024/2025 SCHEDULES\n"
                + "OF-14/A/W/2024VN\nICAO code: " + carrier;
        var document = new WordPermitDocument(text, text, text, List.of(), List.of());
        assertThat(detector.detect(document, "permit.docx").id())
                .isEqualTo(carrier.toLowerCase(java.util.Locale.ROOT)
                        + "-caav-seasonal-overflight-issued");
        String request = "APPLICATION FOR OVERFLIGHT PERMIT\nSCHEDULES\nICAO code: " + carrier;
        var application = new WordPermitDocument(request, request, request, List.of(), List.of());
        assertThatThrownBy(() -> detector.detect(application, "request.docx"))
                .isInstanceOf(vatm.aerosync.common.exception.FormatValidationException.class);
    }

    @Test
    void transnusaKeepsOriginalAndNewInlineFlightsSeparate() throws Exception {
        Path file = tempDir.resolve("tnu.docx");
        try (var document = new XWPFDocument()) {
            for (String line : List.of("HANOI, 17DEC2024",
                    "PERMIT NUMBER OF-7415/12/2024VN #RVS1", "ICAO code: TNU",
                    "2. Schedules: All times in UTC", "Original:",
                    "TNU182-28DEC24-WIII-1135-ZUGY-1700",
                    "TNU183-28DEC24-ZUGY-1800-WIII-2325", "New:",
                    "TNU812-28DEC24-WIII-1135-ZUGY-1700",
                    "TNU813-28DEC24-ZUGY-1800-WIII-2325",
                    "OTHER DETAILS REMAIN UNCHANGED")) {
                document.createParagraph().createRun().setText(line);
            }
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        var permit = parser.parse(file, file.getFileName().toString());
        assertThat(permit.operatorId()).isEqualTo("TNU");
        assertThat(permit.revision()).isTrue();
        assertThat(permit.reviewOnly()).isTrue();
        assertThat(permit.flights()).extracting(f -> f.flightNumber())
                .containsExactly("TNU812", "TNU813");
        assertThat(permit.originalFlights()).extracting(f -> f.flightNumber())
                .containsExactly("TNU182", "TNU183");
        assertThat(permit.flights().getFirst().beginDate()).isEqualTo(LocalDate.of(2024, 12, 28));
        assertThat(permit.flights().getFirst().etd()).isEqualTo("1135");
        assertThat(permit.flights().getFirst().eta()).isEqualTo("1700");
    }

    @Test
    void unitedReadsFirstRowOfHeaderlessTableAndPreservesEta() throws Exception {
        Path file = tempDir.resolve("ual.docx");
        try (var document = new XWPFDocument()) {
            for (String line : List.of("HANOI 08FEB2024",
                    "PERMIT NUMBER O/F-786/02/2024VN", "ICAO code: UAL",
                    "2. Schedules: All times in UTC")) {
                document.createParagraph().createRun().setText(line);
            }
            var table = document.createTable(2, 9);
            String[][] rows = {
                    {"UAL3865", "12FEB24", "12FEB24", "1……", "VHHH", "01:15", "WSSS", "05:00", "B77W"},
                    {"UAL3898", "12FEB24", "12FEB24", "1……", "WSSS", "06:25", "VHHH", "10:05", "B77W"}
            };
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) {
                    table.getRow(r).getCell(c).setText(rows[r][c]);
                }
            }
            document.createParagraph().createRun().setText("3. Purpose of Flight(s): Aircraft Ferry");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        var permit = parser.parse(file, file.getFileName().toString());
        assertThat(permit.operatorId()).isEqualTo("UAL");
        assertThat(permit.flights()).extracting(f -> f.flightNumber())
                .containsExactly("UAL3865", "UAL3898");
        assertThat(permit.flights().getFirst().etd()).isEqualTo("0115");
        assertThat(permit.flights().getFirst().eta()).isEqualTo("0500");
        assertThat(permit.flights().getFirst().sourceAircraftType()).isEqualTo("B77W");
    }
}
