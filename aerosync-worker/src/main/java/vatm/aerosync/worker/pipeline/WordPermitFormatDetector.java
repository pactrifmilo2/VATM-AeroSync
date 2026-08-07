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
        List<DocxPermitFormatProfile> directMatches = profiles.stream()
                .filter(profile -> supports(profile, document, false))
                .toList();
        List<DocxPermitFormatProfile> relaxedMatches = profiles.stream()
                .filter(profile -> supports(profile, document, true))
                .toList();
        List<DocxPermitFormatProfile> matches = preferredMatchSet(directMatches, relaxedMatches);
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

    private List<DocxPermitFormatProfile> preferredMatchSet(
            List<DocxPermitFormatProfile> directMatches,
            List<DocxPermitFormatProfile> relaxedMatches) {
        if (directMatches.isEmpty()) {
            return relaxedMatches;
        }
        int directPriority = highestPriority(directMatches);
        int relaxedPriority = highestPriority(relaxedMatches);
        // Prefer a carrier-specific profile whose only mismatch is an issued-
        // document revision guard over a low-priority generic revision profile.
        // If a dedicated revision profile has equal priority, keep the direct
        // match and its revision-specific mapping.
        return relaxedPriority > directPriority ? relaxedMatches : directMatches;
    }

    private int highestPriority(List<DocxPermitFormatProfile> candidates) {
        return candidates.stream()
                .mapToInt(DocxPermitFormatProfile::priority)
                .max()
                .orElse(Integer.MIN_VALUE);
    }

    private boolean supports(DocxPermitFormatProfile profile,
                             WordPermitDocument document,
                             boolean ignoreRevisionExclusion) {
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
        boolean detectionMatches = profile.detectionPatterns().stream()
                .allMatch(pattern -> (ignoreRevisionExclusion && isRevisionExclusionPattern(pattern))
                        || Pattern.compile(pattern).matcher(document.rawContent()).find()
                        || (hasIata && !hasIcao && isIcaoIdentificationPattern(pattern)));
        return detectionMatches
                && (!ignoreRevisionExclusion
                || Pattern.compile(profile.permit().pattern()).matcher(document.rawContent()).find());
    }

    private boolean isRevisionExclusionPattern(String pattern) {
        String upper = pattern.toUpperCase(Locale.ROOT);
        return upper.contains("(?!")
                && (upper.contains("REV")
                || upper.contains("RVS")
                || upper.contains("SỬA")
                || upper.contains("SUA")
                || upper.contains("THAY")
                || upper.contains("AMEND"));
    }

    private boolean isIcaoIdentificationPattern(String pattern) {
        return pattern.toUpperCase(Locale.ROOT).contains("ICAO");
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }
}
