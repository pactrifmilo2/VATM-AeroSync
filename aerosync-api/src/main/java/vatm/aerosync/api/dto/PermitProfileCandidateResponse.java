package vatm.aerosync.api.dto;

public record PermitProfileCandidateResponse(
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
