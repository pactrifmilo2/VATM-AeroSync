package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class LearnedPermitProfileCompiler {

    private static final int MAX_ANCHOR_LENGTH = 160;

    public CompiledPermitTrainingProfile compile(
            PermitTrainingProfileVersion profile,
            PermitTrainingProfileDefinition definition,
            PermitTrainingDocument primaryDocument) {
        if (profile == null || definition == null || primaryDocument == null) {
            throw new IllegalArgumentException(
                    "Profile, definition, and primary document are required");
        }
        DocumentIndex index = index(primaryDocument);
        List<CompiledPermitTrainingProfile.FieldBinding> fields =
                definition.fields().stream()
                        .map(field -> compileField(field, primaryDocument, index))
                        .toList();
        List<CompiledPermitTrainingProfile.TableBinding> tables =
                definition.tables().stream()
                        .map(table -> compileTable(table, index))
                        .toList();
        return new CompiledPermitTrainingProfile(
                1,
                profile.getProfileKey(),
                profile.getProfileVersion(),
                profile.getDefinitionChecksum(),
                definition.displayName(),
                definition.family(),
                profile.getBaseProfileId(),
                profile.getBaseProfileVersion(),
                fields,
                tables,
                definition.options());
    }

    private CompiledPermitTrainingProfile.FieldBinding compileField(
            PermitTrainingProfileDefinition.FieldMapping field,
            PermitTrainingDocument document,
            DocumentIndex index) {
        return switch (field.source()) {
            case CELL -> {
                CellLocation cell = requireCell(index, field.cellId());
                yield new CompiledPermitTrainingProfile.FieldBinding(
                        field.semanticField(),
                        field.source(),
                        new CompiledPermitTrainingProfile.CellLocator(
                                cell.tableIndex(),
                                cell.rowIndex(),
                                cell.columnIndex(),
                                cell.value()),
                        null,
                        null,
                        field.confirmedValue(),
                        field.required());
            }
            case TEXT -> new CompiledPermitTrainingProfile.FieldBinding(
                    field.semanticField(),
                    field.source(),
                    null,
                    compileTextLocator(
                            field.semanticField(),
                            field.selectedText(),
                            field.confirmedValue(),
                            document.rawContent()),
                    null,
                    field.confirmedValue(),
                    field.required());
            case CONSTANT -> new CompiledPermitTrainingProfile.FieldBinding(
                    field.semanticField(),
                    field.source(),
                    null,
                    null,
                    field.confirmedValue(),
                    field.confirmedValue(),
                    field.required());
        };
    }

    private CompiledPermitTrainingProfile.TextLocator compileTextLocator(
            String semanticField,
            String selectedText,
            String confirmedValue,
            String rawContent) {
        String sampleValue = sampleValue(
                semanticField, selectedText, confirmedValue);
        int selectedOffset = rawContent.indexOf(selectedText);
        if (selectedOffset < 0) {
            throw new IllegalStateException(
                    "Selected text is no longer present for " + semanticField);
        }
        if (rawContent.indexOf(selectedText, selectedOffset + 1) >= 0) {
            throw new IllegalStateException(
                    "Selected text is ambiguous for " + semanticField
                            + "; select a longer unique phrase");
        }
        int valueOffsetInSelection = selectedText.indexOf(sampleValue);
        if (valueOffsetInSelection < 0) {
            throw new IllegalStateException(
                    "Could not identify the selected value for " + semanticField);
        }
        int valueStart = selectedOffset + valueOffsetInSelection;
        int valueEnd = valueStart + sampleValue.length();
        int lineStart = rawContent.lastIndexOf('\n', valueStart - 1) + 1;
        int lineEnd = rawContent.indexOf('\n', valueEnd);
        if (lineEnd < 0) {
            lineEnd = rawContent.length();
        }
        String before = rawContent.substring(lineStart, valueStart);
        String after = rawContent.substring(valueEnd, lineEnd);
        before = tail(before, MAX_ANCHOR_LENGTH);
        after = head(after, MAX_ANCHOR_LENGTH);
        if (before.isBlank() && after.isBlank()) {
            throw new IllegalStateException(
                    "Text mapping for " + semanticField
                            + " needs stable text before or after the selected value");
        }
        return new CompiledPermitTrainingProfile.TextLocator(
                before,
                after,
                sampleValue);
    }

    private String sampleValue(
            String semanticField,
            String selectedText,
            String confirmedValue) {
        if (selectedText == null || selectedText.isBlank()) {
            throw new IllegalStateException(
                    "Selected text is required for " + semanticField);
        }
        if (confirmedValue != null && selectedText.contains(confirmedValue)) {
            return confirmedValue;
        }
        if ("permit.date".equals(semanticField)) {
            String date = LearnedPermitProfileReplayValidator
                    .findDateText(selectedText, confirmedValue);
            if (date != null) {
                return date;
            }
        }
        int separator = Math.max(
                selectedText.lastIndexOf(':'),
                selectedText.lastIndexOf('：'));
        if (separator >= 0 && separator + 1 < selectedText.length()) {
            String value = selectedText.substring(separator + 1).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return selectedText.trim();
    }

    private CompiledPermitTrainingProfile.TableBinding compileTable(
            PermitTrainingProfileDefinition.TableMapping table,
            DocumentIndex index) {
        Map<String, CompiledPermitTrainingProfile.ColumnBinding> columns =
                new TreeMap<>();
        table.columns().forEach((semanticColumn, cellId) -> {
            CellLocation header = requireCell(index, cellId);
            columns.put(
                    semanticColumn,
                    new CompiledPermitTrainingProfile.ColumnBinding(
                            header.columnIndex(),
                            header.value()));
        });
        return new CompiledPermitTrainingProfile.TableBinding(
                table.role(),
                table.tableIndex(),
                table.dataStartRowIndex(),
                columns);
    }

    private DocumentIndex index(PermitTrainingDocument document) {
        Map<String, CellLocation> cells = new HashMap<>();
        for (PermitTrainingDocument.Table table : document.tables()) {
            for (PermitTrainingDocument.Row row : table.rows()) {
                for (PermitTrainingDocument.Cell cell : row.cells()) {
                    cells.put(
                            cell.id(),
                            new CellLocation(
                                    table.index(),
                                    row.index(),
                                    cell.columnIndex(),
                                    cell.value()));
                }
            }
        }
        return new DocumentIndex(Map.copyOf(cells));
    }

    private CellLocation requireCell(DocumentIndex index, String cellId) {
        CellLocation cell = index.cells().get(cellId);
        if (cell == null) {
            throw new IllegalStateException(
                    "Compiled mapping references an unknown cell: " + cellId);
        }
        return cell;
    }

    private String head(String value, int maximum) {
        return value.length() <= maximum
                ? value
                : value.substring(0, maximum);
    }

    private String tail(String value, int maximum) {
        return value.length() <= maximum
                ? value
                : value.substring(value.length() - maximum);
    }

    private record DocumentIndex(Map<String, CellLocation> cells) {
    }

    private record CellLocation(
            int tableIndex,
            int rowIndex,
            int columnIndex,
            String value
    ) {
    }
}
