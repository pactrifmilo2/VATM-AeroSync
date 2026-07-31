package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingCandidateResponse;
import vatm.aerosync.api.dto.PermitTrainingDecisionResponse;
import vatm.aerosync.api.dto.PermitTrainingDecisionRequest;
import vatm.aerosync.api.dto.PermitTrainingGroupResponse;
import vatm.aerosync.api.dto.PermitTrainingPreflightResponse;
import vatm.aerosync.api.service.PermitTrainingCandidateService;
import vatm.aerosync.common.enums.PermitTrainingStatus;

import java.util.List;

@RestController
@RequestMapping("/api/permit-training-candidates")
@Tag(
        name = "Permit training candidates",
        description = "Admin-controlled promotion of approved adaptive parsing evidence.")
@SecurityRequirement(name = "basicAuth")
@PreAuthorize("hasRole('ADMIN')")
public class PermitTrainingCandidateController {

    private final PermitTrainingCandidateService service;

    public PermitTrainingCandidateController(
            PermitTrainingCandidateService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List permit training candidates")
    public PagedResponse<PermitTrainingCandidateResponse> list(
            @RequestParam(required = false) PermitTrainingStatus status,
            @RequestParam(required = false) String profileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, profileId, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a permit training candidate")
    public PermitTrainingCandidateResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/groups")
    @Operation(summary = "Group matching evidence from independent reviews")
    public List<PermitTrainingGroupResponse> groups(
            @RequestParam(required = false) String profileId) {
        return service.groups(profileId);
    }

    @GetMapping("/{id}/preflight")
    @Operation(summary = "Check evidence, conflicts, and validation readiness")
    public PermitTrainingPreflightResponse preflight(@PathVariable Long id) {
        return service.preflight(id);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Get the immutable candidate action history")
    public List<PermitTrainingDecisionResponse> history(
            @PathVariable Long id) {
        return service.history(id);
    }

    @PostMapping("/{id}/validate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Replay retained source permits in the worker")
    public PermitTrainingCandidateResponse validate(
            @PathVariable Long id,
            Authentication authentication) {
        return service.requestValidation(id, authentication.getName());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve and activate a profile-scoped header alias")
    public PermitTrainingCandidateResponse approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false)
            PermitTrainingDecisionRequest request,
            Authentication authentication) {
        return service.approve(
                id,
                request == null ? null : request.comment(),
                authentication.getName());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a permit training candidate")
    public PermitTrainingCandidateResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody PermitTrainingDecisionRequest request,
            Authentication authentication) {
        return service.reject(id, request.comment(), authentication.getName());
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable an active alias immediately")
    public PermitTrainingCandidateResponse disable(
            @PathVariable Long id,
            @Valid @RequestBody PermitTrainingDecisionRequest request,
            Authentication authentication) {
        return service.disable(id, request.comment(), authentication.getName());
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reactivate a disabled alias after fresh validation")
    public PermitTrainingCandidateResponse reactivate(
            @PathVariable Long id,
            @Valid @RequestBody(required = false)
            PermitTrainingDecisionRequest request,
            Authentication authentication) {
        return service.reactivate(
                id,
                request == null ? null : request.comment(),
                authentication.getName());
    }
}
