package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingSourceDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingSourceSummaryResponse;
import vatm.aerosync.api.service.PermitTrainingSourceService;
import vatm.aerosync.common.enums.PermitTrainingSourceState;

@RestController
@RequestMapping("/api/permit-training-sources")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
@Tag(
        name = "Permit training sources",
        description = "Inspect and retain Word permits for future profile training")
public class PermitTrainingSourceController {

    private final PermitTrainingSourceService sourceService;

    public PermitTrainingSourceController(
            PermitTrainingSourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    @Operation(summary = "List captured permit training sources")
    public PagedResponse<PermitTrainingSourceSummaryResponse> list(
            @RequestParam(required = false) PermitTrainingSourceState state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return sourceService.list(state, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one captured permit and its structured document")
    public PermitTrainingSourceDetailResponse get(@PathVariable Long id) {
        return sourceService.get(id);
    }

    @PostMapping("/{id}/retain")
    @Operation(summary = "Retain the source Word file in the training corpus")
    public PermitTrainingSourceDetailResponse retain(@PathVariable Long id) {
        return sourceService.retain(id);
    }
}
