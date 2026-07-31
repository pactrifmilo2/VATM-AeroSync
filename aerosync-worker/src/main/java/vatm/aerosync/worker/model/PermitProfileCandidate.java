package vatm.aerosync.worker.model;

public record PermitProfileCandidate(
        String profileId,
        int profileVersion,
        int priority,
        double confidence,
        int matchedDetectionPatterns,
        int detectionPatternCount,
        boolean permitIdentityMatched,
        boolean scheduleStructureMatched
) {
}
