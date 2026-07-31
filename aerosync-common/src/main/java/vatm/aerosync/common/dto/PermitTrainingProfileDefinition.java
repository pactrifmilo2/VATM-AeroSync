package vatm.aerosync.common.dto;

import java.util.List;
import java.util.Map;

/**
 * Operator-authored, reader-neutral annotations used to build a permit profile.
 * This contract intentionally contains no executable regular expressions.
 */
public record PermitTrainingProfileDefinition(
        int schemaVersion,
        String displayName,
        String family,
        List<FieldMapping> fields,
        List<TableMapping> tables,
        Options options
) {
    public PermitTrainingProfileDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public record FieldMapping(
            String semanticField,
            SourceKind source,
            String cellId,
            String selectedText,
            String confirmedValue,
            boolean required
    ) {
    }

    public record TableMapping(
            TableRole role,
            int tableIndex,
            int dataStartRowIndex,
            Map<String, String> columns
    ) {
        public TableMapping {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }
    }

    public record Options(
            String authorId,
            String permitType,
            String version,
            String season,
            Integer validHours,
            String flightType,
            boolean allowIataAirports,
            boolean emptyAirwaysAllowed,
            boolean reviewOnly
    ) {
    }

    public enum SourceKind {
        CELL,
        TEXT,
        CONSTANT
    }

    public enum TableRole {
        SCHEDULE,
        SUPPLEMENTAL_SCHEDULE,
        ROUTE,
        AIRCRAFT
    }
}
