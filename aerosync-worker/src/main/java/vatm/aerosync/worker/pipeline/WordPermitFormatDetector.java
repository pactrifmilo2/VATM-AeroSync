package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
class WordPermitFormatDetector {

    private final List<DocxPermitFormatProfile> profiles;

    WordPermitFormatDetector(DocxPermitProfileCatalog profileCatalog) {
        this.profiles = profileCatalog.profiles();
    }

    DocxPermitFormatProfile detect(WordPermitDocument document, String fileName) {
        List<DocxPermitFormatProfile> matches = profiles.stream()
                .filter(profile -> supports(profile, document))
                .toList();
        if (matches.isEmpty()) {
            throw invalid(fileName, "Unsupported Word permit format; no format profile matched");
        }
        int highestPriority = matches.stream()
                .mapToInt(DocxPermitFormatProfile::priority)
                .max()
                .orElseThrow();
        List<DocxPermitFormatProfile> preferredMatches = matches.stream()
                .filter(profile -> profile.priority() == highestPriority)
                .toList();
        if (preferredMatches.size() > 1) {
            throw invalid(fileName, "Ambiguous Word permit format profiles: " + preferredMatches.stream()
                    .map(DocxPermitFormatProfile::id)
                    .collect(Collectors.joining(", ")));
        }
        return preferredMatches.getFirst();
    }

    private boolean supports(DocxPermitFormatProfile profile, WordPermitDocument document) {
        boolean hasIata = Pattern.compile(
                        "(?iu)(?:IATA\\s*(?:CODE)?|M[ÃA]\\s*IATA)(?:\\s*\\([^)]*\\))?"
                                + "[ \\t]*:[ \\t]*[A-Z0-9]{2}(?![A-Z0-9])")
                .matcher(document.rawContent())
                .find();
        boolean hasIcao = Pattern.compile(
                        "(?iu)(?:ICAO\\s*(?:CODE)?|M[ÃA]\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                                + "[ \\t]*:[ \\t]*[A-Z0-9]{3}(?![A-Z0-9])")
                .matcher(document.rawContent())
                .find();
        return profile.detectionPatterns().stream()
                .allMatch(pattern -> Pattern.compile(pattern).matcher(document.rawContent()).find()
                        || (hasIata && !hasIcao && isIcaoIdentificationPattern(pattern)));
    }

    private boolean isIcaoIdentificationPattern(String pattern) {
        return pattern.toUpperCase(Locale.ROOT).contains("ICAO");
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }
}
