package vatm.aerosync.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vatm.aerosync.api.dto.TestReplayRequest;
import vatm.aerosync.api.dto.TestReplayResponse;
import vatm.aerosync.api.service.TestReplayService;

@RestController
@RequestMapping("/api/testing/jobs")
@Tag(name = "Test replay", description = "Explicitly enabled destructive helpers for permit-ingestion testing.")
public class TestReplayController {

    private final TestReplayService testReplayService;

    public TestReplayController(TestReplayService testReplayService) {
        this.testReplayService = testReplayService;
    }

    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Reset and replay an email permit",
            description = "Deletes only the matching ATFM permit written by AEROSYNC, resets its tracking attempt, "
                    + "and republishes the archived email attachment. Disabled by default.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Replay queued"),
            @ApiResponse(responseCode = "400", description = "Wrong permit confirmation or unsupported job"),
            @ApiResponse(responseCode = "404", description = "Job not found"),
            @ApiResponse(responseCode = "409", description = "Replay disabled or unsafe in the current state")
    })
    public TestReplayResponse replay(@PathVariable Long id,
                                     @Valid @RequestBody TestReplayRequest request) {
        return testReplayService.replay(id, request.confirmPermitId());
    }
}
