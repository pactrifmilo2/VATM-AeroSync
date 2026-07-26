package vatm.aerosync.worker.pipeline;

import java.util.List;

/**
 * Reader-neutral representation of a Word permit. Both OOXML (.docx) and
 * binary Word (.doc) readers produce this model before format detection.
 */
record WordPermitDocument(
        String paragraphText,
        String tableText,
        String rawContent,
        List<List<List<String>>> tables,
        List<String> tableContexts
) {
    WordPermitDocument {
        tables = List.copyOf(tables);
        tableContexts = List.copyOf(tableContexts);
        if (tables.size() != tableContexts.size()) {
            throw new IllegalArgumentException("Each Word table must have a matching text context");
        }
    }
}
