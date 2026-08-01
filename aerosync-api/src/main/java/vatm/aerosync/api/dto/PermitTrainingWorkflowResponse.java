package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

import java.util.List;

public record PermitTrainingWorkflowResponse(
        Long profileId,
        String profileKey,
        int profileVersion,
        PermitTrainingProfileStatus status,
        long version,
        String currentStep,
        PermitTrainingSourceDetailResponse source,
        PermitReviewSnapshot expectedPermit,
        List<Suggestion> suggestions,
        List<String> unresolved,
        Progress progress,
        PermitTrainingProfileCanaryReadinessResponse readiness,
        Actions actions
) {
    public PermitTrainingWorkflowResponse {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    public record Suggestion(
            String semanticField,
            String label,
            String source,
            String cellId,
            String selectedText,
            String confirmedValue,
            double confidence,
            boolean automatic,
            String message) {
    }

    public record Progress(
            int trainingExamples,
            int requiredTrainingExamples,
            int unseenPassed,
            int requiredUnseen,
            int unseenPending,
            int unseenFailed) {
    }

    public record Actions(
            boolean canEditPermit,
            boolean canResolve,
            boolean canValidate,
            boolean canAddExample,
            boolean canActivate,
            boolean canDisable,
            boolean canRollback) {
    }
}
