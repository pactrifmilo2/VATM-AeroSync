package vatm.aerosync.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RuntimeConfigDto(
        @Min(60_000) long schedulerFixedDelayMs,
        @Min(1) @Max(100) int maxFilesPerCycle,
        @NotEmpty List<String> whitelistSenders
) {
}
