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
        if (matches.size() > 1) {
            throw invalid(fileName, "Ambiguous Word permit format profiles: " + matches.stream()
                    .map(DocxPermitFormatProfile::id)
                    .collect(Collectors.joining(", ")));
        }
        return matches.getFirst();
    }

    private boolean supports(DocxPermitFormatProfile profile, WordPermitDocument document) {
        return profile.detectionPatterns().stream()
                .allMatch(pattern -> Pattern.compile(pattern).matcher(document.rawContent()).find());
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }
}
