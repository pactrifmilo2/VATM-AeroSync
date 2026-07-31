package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitApprovalRequest;
import vatm.aerosync.api.dto.PermitCorrectionRequest;
import vatm.aerosync.api.dto.PermitRejectionRequest;
import vatm.aerosync.api.dto.PermitReviewDetailResponse;
import vatm.aerosync.api.dto.PermitReviewSummaryResponse;
import vatm.aerosync.api.service.PermitReviewService;
import vatm.aerosync.common.enums.PermitReviewStatus;

@RestController
@RequestMapping("/api/permit-reviews")
@Tag(name = "Permit reviews", description = "Operator review and controlled publication of adaptive permit parses.")
@SecurityRequirement(name = "basicAuth")
public class PermitReviewController {

    private final PermitReviewService permitReviewService;

    public PermitReviewController(PermitReviewService permitReviewService) {
        this.permitReviewService = permitReviewService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "List permit reviews")
    public PagedResponse<PermitReviewSummaryResponse> list(
            @RequestParam(required = false) PermitReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return permitReviewService.list(status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Get a permit review")
    public PermitReviewDetailResponse get(@PathVariable Long id) {
        return permitReviewService.get(id);
    }

    @PutMapping("/{id}/correction")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Save an operator correction")
    public PermitReviewDetailResponse correct(@PathVariable Long id,
                                              @Valid @RequestBody PermitCorrectionRequest request,
                                              Authentication authentication) {
        return permitReviewService.correct(
                id, request.permit(), request.comment(), authentication.getName());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Approve the reviewed permit data")
    public PermitReviewDetailResponse approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) PermitApprovalRequest request,
            Authentication authentication) {
        return permitReviewService.approve(
                id, request == null ? null : request.comment(), authentication.getName());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Reject the reviewed permit data")
    public PermitReviewDetailResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody PermitRejectionRequest request,
            Authentication authentication) {
        return permitReviewService.reject(id, request.reason(), authentication.getName());
    }

    @PostMapping("/{id}/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Publish an approved permit to ATFM")
    public PermitReviewDetailResponse publish(@PathVariable Long id,
                                              Authentication authentication) {
        return permitReviewService.requestPublish(id, authentication.getName());
    }
}
