package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.model.PermitParseWarning;
import vatm.aerosync.worker.model.PermitProfileCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
class WordPermitFormatDetector {

    static final double MINIMUM_CONFIDENCE = 0.90;
    static final double MINIMUM_RUNNER_UP_MARGIN = 0.15;

    private final DocxPermitProfileCatalog profileCatalog;
    private final PermitSemanticExtractor semanticExtractor;

    WordPermitFormatDetector(DocxPermitProfileCatalog profileCatalog) {
        this(profileCatalog, new PermitSemanticExtractor());
    }

    @Autowired
    WordPermitFormatDetector(DocxPermitProfileCatalog profileCatalog,
                             PermitSemanticExtractor semanticExtractor) {
        this.profileCatalog = profileCatalog;
        this.semanticExtractor = semanticExtractor;
    }

    DocxPermitFormatProfile detect(WordPermitDocument document, String fileName) {
        return detectResult(document, fileName).profile();
    }

    WordPermitDetectionResult detectResult(WordPermitDocument document, String fileName) {
        PermitSemanticEvidence semantics = semanticExtractor.extract(document);
        DocxPermitProfileCatalog.ActiveProfiles activeProfiles =
                profileCatalog.activeProfiles();
        List<ScoredProfile> scores = activeProfiles.profiles().stream()
                .map(profile -> score(
                        profile,
                        activeProfiles.declaredProfile(profile.id()),
                        document,
                        semantics))
                .sorted(Comparator
                        .comparingInt((ScoredProfile score) -> score.profile().priority())
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(ScoredProfile::confidence).reversed())
                        .thenComparing(score -> score.profile().id()))
                .toList();
        List<ScoredProfile> eligible = scores.stream()
                .filter(score -> score.confidence() >= MINIMUM_CONFIDENCE)
                .toList();
        if (eligible.isEmpty()) {
            throw invalid(fileName,
                    "Unsupported Word permit format; best profile candidates: "
                            + scores.stream()
                            .limit(3)
                            .map(score -> "%s=%.2f".formatted(
                                    score.profile().id(), score.confidence()))
                            .collect(Collectors.joining(", ")));
        }

        ScoredProfile winner = eligible.getFirst();
        List<ScoredProfile> samePriority = eligible.stream()
                .filter(score -> score.profile().priority() == winner.profile().priority())
                .toList();
        ScoredProfile runnerUp = samePriority.size() > 1 ? samePriority.get(1) : null;
        double margin = runnerUp == null
                ? 1.0
                : winner.confidence() - runnerUp.confidence();
        if (runnerUp != null && Math.abs(margin) < 0.000001) {
            throw invalid(fileName, "Ambiguous Word permit format profiles: " + samePriority.stream()
                    .filter(score -> Math.abs(score.confidence() - winner.confidence()) < 0.000001)
                    .map(score -> score.profile().id())
                    .collect(Collectors.joining(", ")));
        }

        List<PermitParseWarning> warnings = new ArrayList<>();
        if (winner.matchedPatterns() < winner.patternCount()) {
            warnings.add(new PermitParseWarning(
                    "PROFILE_DETECTION_PARTIAL",
                    "Only %d of %d detection signals matched profile %s".formatted(
                            winner.matchedPatterns(), winner.patternCount(), winner.profile().id()),
                    true));
        }
        if (winner.scheduleMatch() != null && winner.scheduleMatch().requiresReview()) {
            warnings.add(new PermitParseWarning(
                    "ADAPTIVE_TABLE_HEADER",
                    "Schedule columns required multi-row, shared-alias, or fuzzy matching",
                    true));
        }
        if (!winner.exactMatch()
                && runnerUp != null
                && margin < MINIMUM_RUNNER_UP_MARGIN) {
            warnings.add(new PermitParseWarning(
                    "PROFILE_MARGIN_LOW",
                    "Profile %s leads %s by only %.2f".formatted(
                            winner.profile().id(), runnerUp.profile().id(), margin),
                    true));
        }
        if (winner.profile().validation() != null
                && winner.profile().validation().reviewOnly()) {
            warnings.add(new PermitParseWarning(
                    "PROFILE_REVIEW_ONLY",
                    "Profile " + winner.profile().id() + " is configured for review-only processing",
                    true));
        }

        List<PermitProfileCandidate> candidates = scores.stream()
                .map(ScoredProfile::candidate)
                .toList();
        boolean reviewRequired = warnings.stream().anyMatch(PermitParseWarning::reviewRequired);
        return new WordPermitDetectionResult(
                winner.profile(),
                activeProfiles.declaredProfile(winner.profile().id()),
                winner.confidence(),
                margin,
                reviewRequired,
                candidates,
                warnings,
                semantics);
    }

    private ScoredProfile score(DocxPermitFormatProfile profile,
                                DocxPermitFormatProfile declared,
                                WordPermitDocument document,
                                PermitSemanticEvidence semantics) {
        int patternCount = profile.detectionPatterns().size();
        int matchedPatterns = (int) profile.detectionPatterns().stream()
                .filter(pattern -> Pattern.compile(pattern).matcher(document.rawContent()).find())
                .count();
        Pattern permitPattern = Pattern.compile(profile.permit().pattern());
        boolean permitIdentity = semantics.permitIdentities().stream()
                .map(PermitSemanticEvidence.PermitIdentityCandidate::canonicalValue)
                .anyMatch(candidate -> permitPattern.matcher(candidate).find())
                || permitPattern.matcher(document.rawContent()).find();
        WordPermitTableMatcher.TableMatch scheduleMatch = WordPermitTableMatcher.find(
                document.tables(),
                document.tableContexts(),
                profile.schedule().columns(),
                declared.schedule().columns(),
                profile.schedule().requiredColumns(),
                profile.schedule().excludeColumns(),
                List.of(),
                profile.schedule().lastMatchingTable());
        double patternScore = patternCount == 0 ? 0.0 : (double) matchedPatterns / patternCount;
        double confidence = (permitIdentity ? 0.40 : 0.0)
                + (scheduleMatch != null ? 0.40 : 0.0)
                + (patternScore * 0.20);
        return new ScoredProfile(
                profile,
                confidence,
                matchedPatterns,
                patternCount,
                permitIdentity,
                scheduleMatch);
    }

    private FormatValidationException invalid(String fileName, String detail) {
        return new FormatValidationException(fileName, detail);
    }

    private record ScoredProfile(
            DocxPermitFormatProfile profile,
            double confidence,
            int matchedPatterns,
            int patternCount,
            boolean permitIdentity,
            WordPermitTableMatcher.TableMatch scheduleMatch
    ) {
        boolean exactMatch() {
            return permitIdentity
                    && matchedPatterns == patternCount
                    && scheduleMatch != null
                    && !scheduleMatch.requiresReview();
        }

        PermitProfileCandidate candidate() {
            return new PermitProfileCandidate(
                    profile.id(),
                    profile.profileVersion(),
                    profile.priority(),
                    confidence,
                    matchedPatterns,
                    patternCount,
                    permitIdentity,
                    scheduleMatch != null);
        }
    }
}
