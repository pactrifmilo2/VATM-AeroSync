package vatm.aerosync.common.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Stable, reader-neutral evidence exposed to the guided permit trainer.
 */
public record PermitTrainingDocument(
        String paragraphText,
        String tableText,
        String rawContent,
        List<Table> tables,
        LocalDate authoredDate
) {
    public PermitTrainingDocument {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public record Table(
            int index,
            String context,
            List<Row> rows
    ) {
        public Table {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record Row(
            int index,
            List<Cell> cells
    ) {
        public Row {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record Cell(
            String id,
            int rowIndex,
            int columnIndex,
            String value
    ) {
    }
}
