package vatm.aerosync.api.dto;

import jakarta.validation.constraints.Size;

public record PermitApprovalRequest(
        @Size(max = 2000) String comment
) {
}
