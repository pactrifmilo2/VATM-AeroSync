package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CaavSeasonalPermitOcrVariantTest {

    private final DocxPermitProfileCatalog catalog = new DocxPermitProfileCatalog();
    private final WordPermitFormatDetector detector = new WordPermitFormatDetector(catalog);

    @Test
    void detectsSeasonalLandingPermitWithNumeroAndUnicodeSpacing() {
        String numero = new String(Character.toChars(0x2116));
        String permitText = numero + ": LD\u00a0\u00a0-    72/A/W/2026VN";
        String rawContent = permitText + "\nREVISION OF ISSUED PERMIT\nNEW SCHEDULE";
        WordPermitDocument document = new WordPermitDocument(
                rawContent, rawContent, rawContent, List.of(), List.of());
        DocxPermitFormatProfile profile = detector.detect(document, "LD-72-A-W-2026VN.docx");
        var matcher = Pattern.compile(profile.permit().pattern()).matcher(permitText);

        assertThat(profile.id()).isEqualTo("caav-generic-seasonal-landing-revision");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("number")).isEqualTo("72");
        assertThat(matcher.group("version")).isEqualTo("A");
        assertThat(matcher.group("season")).isEqualTo("W");
        assertThat(matcher.group("year")).isEqualTo("2026");
    }

    @Test
    void issuedLandingPatternAcceptsNumeroAndMultipleUnicodeSpaces() {
        DocxPermitFormatProfile profile = catalog.profiles().stream()
                .filter(candidate -> candidate.id().equals("caav-generic-landing-issued"))
                .findFirst()
                .orElseThrow();
        String permitText = new String(Character.toChars(0x2116)) + ": LD\u00a0\u00a0-    72/8/2026VN";
        var matcher = Pattern.compile(profile.permit().pattern()).matcher(permitText);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("number")).isEqualTo("72");
        assertThat(matcher.group("month")).isEqualTo("8");
        assertThat(matcher.group("year")).isEqualTo("2026");
    }
    @Test
    void everyPermitProfileCanUseTheSharedNumeroNormalization() {
        var profiles = catalog.profiles().stream()
                .filter(profile -> profile.permit() != null && profile.permit().pattern() != null)
                .toList();

        assertThat(profiles).hasSize(358);
        String normalized = PermitIdentityText.normalize(
                new String(Character.toChars(0x2116)) + ": OF-123/8/2026VN");
        assertThat(profiles.stream()
                .filter(profile -> Pattern.compile(profile.permit().pattern()).matcher(normalized).find())
                .count()).isGreaterThan(0);
    }
    @Test
    void sharedPermitNormalizerRemovesNumeroAndPreservesPermitText() {
        assertThat(PermitIdentityText.normalize("№: LD\u00a0\u00a0-    72/A/W/2026VN"))
                .isEqualTo("LD\u00a0\u00a0-    72/A/W/2026VN");
    }
}