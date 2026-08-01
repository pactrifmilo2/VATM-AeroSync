package vatm.aerosync.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;

import java.util.List;
import java.util.Map;

public final class PermitTrainingWorkflowRequests {

    private PermitTrainingWorkflowRequests() {
    }

    public record Start(@NotNull Long sourceId) {
    }

    public record ExpectedPermit(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull Long sourceId,
            @NotNull @Valid PermitReviewSnapshot permit) {
    }

    public record Resolutions(
            @NotNull @PositiveOrZero Long expectedVersion,
            List<@Valid FieldResolution> fields,
            @Valid ScheduleResolution schedule,
            @Valid TableResolution route,
            @Valid TableResolution aircraft) {
        public Resolutions {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record FieldResolution(
            @NotNull String semanticField,
            @NotNull PermitTrainingProfileDefinition.SourceKind source,
            String cellId,
            String selectedText,
            String confirmedValue,
            boolean required) {
    }

    public record ScheduleResolution(
            int tableIndex,
            int dataStartRowIndex,
            Map<String, String> columns) {
        public ScheduleResolution {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }
    }

    public record TableResolution(
            int tableIndex,
            int dataStartRowIndex,
            Map<String, String> columns) {
        public TableResolution {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }
    }

    public record Example(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull Long sourceId,
            @NotNull @Valid PermitReviewSnapshot permit) {
    }

    public record Validate(
            @NotNull @PositiveOrZero Long expectedVersion) {
    }
}
