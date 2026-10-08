package vatm.aerosync.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RuntimeConfigDto(
        @Min(60_000) long schedulerFixedDelayMs,
        @NotBlank @Pattern(regexp = "EMAIL|FOLDER|BOTH") String ingestionMode,
        @Min(10_000) long folderPollingIntervalMs,
        @Min(1) @Max(100) int maxFilesPerCycle,
        List<String> blacklistSenders,
        @Size(max = 1000) List<@Email @NotBlank String> allowedSenders,
        @NotBlank String incomingDir,
        @NotBlank String processedDir,
        @NotBlank String errorDir,
        @NotBlank String emailHost,
        @Min(1) @Max(65_535) int emailPort,
        @NotBlank String emailProtocol,
        @NotBlank String emailUser,
        String emailPassword,
        @NotBlank String retryMode,
        @Min(1) int maxSizePerFileMb,
        boolean autoQuarantine,
        boolean skipDuplicateIdempotency,
        boolean sendZaloAlert
) {
    public RuntimeConfigDto(long schedulerFixedDelayMs, String ingestionMode, long folderPollingIntervalMs,
                            int maxFilesPerCycle, List<String> blacklistSenders, String incomingDir,
                            String processedDir, String errorDir, String emailHost, int emailPort,
                            String emailProtocol, String emailUser, String emailPassword, String retryMode,
                            int maxSizePerFileMb, boolean autoQuarantine, boolean skipDuplicateIdempotency,
                            boolean sendZaloAlert) {
        this(schedulerFixedDelayMs, ingestionMode, folderPollingIntervalMs, maxFilesPerCycle,
                blacklistSenders, List.of(), incomingDir, processedDir, errorDir, emailHost, emailPort,
                emailProtocol, emailUser, emailPassword, retryMode, maxSizePerFileMb, autoQuarantine,
                skipDuplicateIdempotency, sendZaloAlert);
    }
}
