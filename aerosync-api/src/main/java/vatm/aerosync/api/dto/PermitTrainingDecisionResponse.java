package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingAction;

import java.time.LocalDateTime;

public record PermitTrainingDecisionResponse(
        Long id,
        Long candidateId,
        PermitTrainingAction action,
        String actor,
        String comment,
        LocalDateTime createdAt
) {
}
