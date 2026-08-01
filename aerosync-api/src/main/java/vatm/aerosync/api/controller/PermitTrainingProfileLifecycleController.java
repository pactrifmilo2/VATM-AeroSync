package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileLifecycleRequests;
import vatm.aerosync.api.service.PermitTrainingProfileLifecycleService;

@RestController
@RequestMapping("/api/permit-training-profiles")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
@Tag(name = "Learned permit profile lifecycle")
public class PermitTrainingProfileLifecycleController {

    private final PermitTrainingProfileLifecycleService service;

    public PermitTrainingProfileLifecycleController(
            PermitTrainingProfileLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a fully tested review-only learned format")
    public PermitTrainingProfileDetailResponse activate(
            @PathVariable Long id,
            @Valid @RequestBody
            PermitTrainingProfileLifecycleRequests.Activate request,
            Authentication authentication) {
        return service.activate(
                id, request.expectedVersion(), request.acknowledgement(),
                authentication.getName());
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Stop an active learned format")
    public PermitTrainingProfileDetailResponse disable(
            @PathVariable Long id,
            @Valid @RequestBody
            PermitTrainingProfileLifecycleRequests.Disable request,
            Authentication authentication) {
        return service.disable(
                id, request.expectedVersion(), request.reason(),
                authentication.getName());
    }

    @PostMapping("/{id}/rollback")
    @Operation(summary = "Restore a previously active learned format version")
    public PermitTrainingProfileDetailResponse rollback(
            @PathVariable Long id,
            @Valid @RequestBody
            PermitTrainingProfileLifecycleRequests.Rollback request,
            Authentication authentication) {
        return service.rollback(
                id, request.expectedVersion(), request.targetProfileId(),
                request.reason(), authentication.getName());
    }
}
