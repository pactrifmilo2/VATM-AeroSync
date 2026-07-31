package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

import java.time.LocalDateTime;
import java.util.List;

public record PermitTrainingProfileDetailResponse(
        Long id,
        String profileKey,
        int profileVersion,
        PermitTrainingProfileStatus status,
        String baseProfileId,
        Integer baseProfileVersion,
        int schemaVersion,
        PermitTrainingProfileDefinition definition,
        String definitionChecksum,
        int evidenceCount,
        int canarySuccessCount,
        String createdBy,
        String confirmedBy,
        LocalDateTime confirmedAt,
        String lastError,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<PermitTrainingProfileEvidenceResponse> evidence,
        List<PermitTrainingProfileEventResponse> history
) {
    public PermitTrainingProfileDetailResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        history = history == null ? List.of() : List.copyOf(history);
    }
}
