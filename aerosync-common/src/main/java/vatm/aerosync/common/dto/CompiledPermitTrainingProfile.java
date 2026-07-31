package vatm.aerosync.common.dto;

import java.util.List;
import java.util.Map;

/**
 * Safe, declarative runtime artifact produced from an operator-confirmed
 * training definition. It contains selectors and column indexes only: no
 * regular expressions, scripts, or executable class names are accepted.
 */
public record CompiledPermitTrainingProfile(
        int schemaVersion,
        String profileKey,
        int profileVersion,
        String definitionChecksum,
        String displayName,
        String family,
        String baseProfileId,
        Integer baseProfileVersion,
        List<FieldBinding> fields,
        List<TableBinding> tables,
        PermitTrainingProfileDefinition.Options options
) {
    public CompiledPermitTrainingProfile {
        fields = fields == null ? List.of() : List.copyOf(fields);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public record FieldBinding(
            String semanticField,
            PermitTrainingProfileDefinition.SourceKind source,
            CellLocator cell,
            TextLocator text,
            String constantValue,
            String confirmedSampleValue,
            boolean required
    ) {
    }

    public record CellLocator(
            int tableIndex,
            int rowIndex,
            int columnIndex,
            String sampleValue
    ) {
    }

    public record TextLocator(
            String anchorBefore,
            String anchorAfter,
            String sampleValue
    ) {
    }

    public record TableBinding(
            PermitTrainingProfileDefinition.TableRole role,
            int tableIndex,
            int dataStartRowIndex,
            Map<String, ColumnBinding> columns
    ) {
        public TableBinding {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }
    }

    public record ColumnBinding(
            int columnIndex,
            String sampleHeader
    ) {
    }
}
