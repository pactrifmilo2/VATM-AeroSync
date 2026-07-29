package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;

import java.util.List;
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
        return profile.detectionPatterns().stream()
                .allMatch(pattern -> Pattern.compile(pattern).matcher(document.rawContent()).find());
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }
}
