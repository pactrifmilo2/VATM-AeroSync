package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileConfirmRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileCreateRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileEvidenceRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileSummaryResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileUpdateRequest;
import vatm.aerosync.api.service.PermitTrainingProfileService;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

@RestController
@RequestMapping("/api/permit-training-profiles")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
@Tag(
        name = "Permit training profiles",
        description = "Guided, non-activating permit profile definition and evidence collection")
@SecurityRequirement(name = "basicAuth")
public class PermitTrainingProfileController {

    private final PermitTrainingProfileService service;

    public PermitTrainingProfileController(
            PermitTrainingProfileService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List guided training profile versions")
    public PagedResponse<PermitTrainingProfileSummaryResponse> list(
            @RequestParam(required = false)
            PermitTrainingProfileStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a profile definition, evidence, and history")
    public PermitTrainingProfileDetailResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the next draft version from a retained source")
    public PermitTrainingProfileDetailResponse create(
            @Valid @RequestBody PermitTrainingProfileCreateRequest request,
            Authentication authentication) {
        return service.create(request, authentication.getName());
    }

    @PutMapping("/{id}/definition")
    @Operation(summary = "Save validated field and table annotations")
    public PermitTrainingProfileDetailResponse updateDefinition(
            @PathVariable Long id,
            @Valid @RequestBody PermitTrainingProfileUpdateRequest request,
            Authentication authentication) {
        return service.updateDefinition(
                id,
                request.expectedVersion(),
                request.definition(),
                authentication.getName());
    }

    @PostMapping("/{id}/evidence")
    @Operation(summary = "Attach an operator-confirmed expected permit")
    public PermitTrainingProfileDetailResponse attachEvidence(
            @PathVariable Long id,
            @Valid @RequestBody PermitTrainingProfileEvidenceRequest request,
            Authentication authentication) {
        return service.attachEvidence(
                id,
                request,
                authentication.getName());
    }

    @DeleteMapping("/{id}/evidence/{evidenceId}")
    @Operation(summary = "Remove evidence from an unconfirmed draft")
    public PermitTrainingProfileDetailResponse removeEvidence(
            @PathVariable Long id,
            @PathVariable Long evidenceId,
            @RequestParam long expectedVersion,
            Authentication authentication) {
        return service.removeEvidence(
                id,
                evidenceId,
                expectedVersion,
                authentication.getName());
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Lock the guided mapping and begin evidence collection")
    public PermitTrainingProfileDetailResponse confirmMapping(
            @PathVariable Long id,
            @Valid @RequestBody PermitTrainingProfileConfirmRequest request,
            Authentication authentication) {
        return service.confirmMapping(
                id,
                request.expectedVersion(),
                authentication.getName());
    }
}
