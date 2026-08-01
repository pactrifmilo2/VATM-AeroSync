package vatm.aerosync.api.dto;

public record TestReplayResponse(
        Long jobId,
        String normalizedPermitId,
        int deletedTargetMasterRows,
        int deletedTargetDetailRows,
        boolean replayQueued) {
}
