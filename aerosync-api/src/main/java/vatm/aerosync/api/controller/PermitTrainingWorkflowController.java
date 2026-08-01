package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.PermitTrainingWorkflowRequests;
import vatm.aerosync.api.dto.PermitTrainingWorkflowResponse;
import vatm.aerosync.api.service.PermitTrainingWorkflowService;

@RestController
@RequestMapping("/api/permit-training-workflows")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
@Tag(name = "Assisted permit training")
public class PermitTrainingWorkflowController {

    private final PermitTrainingWorkflowService service;

    public PermitTrainingWorkflowController(
            PermitTrainingWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start or resume assisted training from a Word source")
    public PermitTrainingWorkflowResponse start(
            @Valid @RequestBody PermitTrainingWorkflowRequests.Start request,
            Authentication authentication) {
        return service.start(request.sourceId(), authentication.getName());
    }

    @GetMapping("/{profileId}")
    @Operation(summary = "Read the complete assisted training workflow")
    public PermitTrainingWorkflowResponse get(@PathVariable Long profileId) {
        return service.view(profileId);
    }

    @PutMapping("/{profileId}/expected-permit")
    @Operation(summary = "Save the corrected permit and infer its layout")
    public PermitTrainingWorkflowResponse expectedPermit(
            @PathVariable Long profileId,
            @Valid @RequestBody
            PermitTrainingWorkflowRequests.ExpectedPermit request,
            Authentication authentication) {
        return service.saveExpectedPermit(
                profileId, request, authentication.getName());
    }

    @PutMapping("/{profileId}/resolutions")
    @Operation(summary = "Resolve fields that could not be inferred safely")
    public PermitTrainingWorkflowResponse resolutions(
            @PathVariable Long profileId,
            @Valid @RequestBody PermitTrainingWorkflowRequests.Resolutions request,
            Authentication authentication) {
        return service.saveResolutions(
                profileId, request, authentication.getName());
    }

    @PostMapping("/{profileId}/examples")
    @Operation(summary = "Add a corrected training example or unseen test")
    public PermitTrainingWorkflowResponse example(
            @PathVariable Long profileId,
            @Valid @RequestBody PermitTrainingWorkflowRequests.Example request,
            Authentication authentication) {
        return service.addExample(
                profileId, request, authentication.getName());
    }

    @PostMapping("/{profileId}/validate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Confirm, compile, and test the learned format")
    public PermitTrainingWorkflowResponse validate(
            @PathVariable Long profileId,
            @Valid @RequestBody PermitTrainingWorkflowRequests.Validate request,
            Authentication authentication) {
        return service.validate(
                profileId, request, authentication.getName());
    }
}
