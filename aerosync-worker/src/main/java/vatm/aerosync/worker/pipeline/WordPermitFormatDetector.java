package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
class WordPermitFormatDetector {

    private static final Pattern IATA_IDENTITY = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|M(?:ã|Ã|A)\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{2})(?![A-Z0-9])");
    private static final Pattern ICAO_IDENTITY = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|M(?:ã|Ã|A)\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "[ \\t]*:[ \\t]*(?<value>[A-Z0-9]{3})(?![A-Z0-9])");

    private final List<DocxPermitFormatProfile> profiles;
    private final PermitOperatorCatalog operatorCatalog = new PermitOperatorCatalog();

    WordPermitFormatDetector(DocxPermitProfileCatalog profileCatalog) {
        this.profiles = profileCatalog.profiles();
    }

    DocxPermitFormatProfile detect(WordPermitDocument document, String fileName) {
        String permitText = PermitIdentityText.normalize(document.rawContent());
        CarrierIdentity carrier = carrierIdentity(permitText);
        List<DocxPermitFormatProfile> directMatches = profiles.stream()
                .filter(profile -> supports(profile, permitText, false))
                .toList();
        List<DocxPermitFormatProfile> relaxedMatches = profiles.stream()
                .filter(profile -> supports(profile, permitText, true))
                .toList();
        List<DocxPermitFormatProfile> matches = preferredMatchSet(directMatches, relaxedMatches);
        if (carrier.icao() != null) {
            List<DocxPermitFormatProfile> directCarrierMatches = directMatches.stream()
                    .filter(profile -> !isCaavFallback(profile))
                    .toList();
            List<DocxPermitFormatProfile> relaxedCarrierMatches = relaxedMatches.stream()
                    .filter(profile -> !isCaavFallback(profile))
                    .toList();
            List<DocxPermitFormatProfile> carrierMatches = preferredMatchSet(
                    directCarrierMatches, relaxedCarrierMatches);
            if (carrierMatches.isEmpty()) {
                throw invalid(fileName,
                        "File ch\u01b0a c\u00f3 \u0111\u1ecbnh d\u1ea1ng YAML ph\u00f9 h\u1ee3p.");
            }
            matches = carrierMatches.stream()
                    .filter(profile -> permitIdentityMatches(profile, permitText))
                    .toList();
            if (matches.isEmpty()) {
                throw invalid(fileName,
                        "File ch\u01b0a c\u00f3 \u0111\u1ecbnh d\u1ea1ng YAML ph\u00f9 h\u1ee3p.");
            }
        }
        if (matches.isEmpty()) {
            if (carrier.icao() != null) {
                throw invalid(fileName,
                        "File ch\u01b0a c\u00f3 \u0111\u1ecbnh d\u1ea1ng YAML ph\u00f9 h\u1ee3p.");
            }
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

    private CarrierIdentity carrierIdentity(String rawContent) {
        var iata = IATA_IDENTITY.matcher(rawContent);
        var icao = ICAO_IDENTITY.matcher(rawContent);
        return new CarrierIdentity(
                iata.find() ? iata.group("value").toUpperCase(Locale.ROOT) : null,
                icao.find() ? icao.group("value").toUpperCase(Locale.ROOT) : null);
    }

    private boolean permitIdentityMatches(DocxPermitFormatProfile profile, String rawContent) {
        return profile.permit() != null
                && profile.permit().pattern() != null
                && Pattern.compile(profile.permit().pattern())
                        .matcher(joinSplitDigits(PermitIdentityText.normalize(rawContent))).find();
    }

    private boolean isCaavFallback(DocxPermitFormatProfile profile) {
        return profile.id().toLowerCase(Locale.ROOT).startsWith("caav-generic-");
    }

    private List<DocxPermitFormatProfile> preferredMatchSet(
            List<DocxPermitFormatProfile> directMatches,
            List<DocxPermitFormatProfile> relaxedMatches) {
        if (directMatches.isEmpty()) {
            return relaxedMatches;
        }
        if (directMatches.stream().anyMatch(profile -> !isCaavFallback(profile))) {
            return directMatches;
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
                             String permitText,
                             boolean ignoreRevisionExclusion) {
        CarrierIdentity identity = carrierIdentity(permitText);
        // Missing ICAO is not permission to match every carrier. Relax an ICAO
        // guard only when an unambiguous catalog IATA maps to this fixed operator.
        String inferredOperator = identity.iata() == null ? null : operatorCatalog.operatorForIata(identity.iata());
        boolean confirmedIataOnly = identity.icao() == null && inferredOperator != null
                && profile.operator() != null && profile.operator().fixedValue() != null
                && inferredOperator.equalsIgnoreCase(profile.operator().fixedValue());
        boolean detectionMatches = profile.detectionPatterns().stream()
                .allMatch(pattern -> (ignoreRevisionExclusion && isRevisionExclusionPattern(pattern))
                        || matchesPattern(pattern, permitText)
                        || (confirmedIataOnly && isIcaoIdentificationPattern(pattern)));
        return detectionMatches
                && (!ignoreRevisionExclusion
                || Pattern.compile(profile.permit().pattern())
                        .matcher(joinSplitDigits(permitText)).find());
    }

    private boolean matchesPattern(String pattern, String rawContent) {
        Pattern compiled = Pattern.compile(pattern);
        if (compiled.matcher(rawContent).find()) {
            return true;
        }
        // A number of legacy YAML profiles contain the UTF-8 mojibake "MÃ"
        // while incoming Vietnamese documents correctly contain "Mã".
        return compiled.matcher(rawContent.replace("Mã", "MÃ")).find();
    }

    private String joinSplitDigits(String value) {
        return value == null ? "" : value.replaceAll("(?<=\\d)[ \\t]+(?=\\d)", "");
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

    private record CarrierIdentity(String iata, String icao) {
    }
}
