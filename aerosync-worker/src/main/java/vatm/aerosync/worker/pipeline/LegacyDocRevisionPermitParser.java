package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compatibility facade for the original legacy-DOC parser entry point. DOC
 * and DOCX now use the same reader, detector, profiles and permit mapper.
 */
@Component
public class LegacyDocRevisionPermitParser {

    private final DocxSchedulePermitParser wordPermitParser;

    public LegacyDocRevisionPermitParser() {
        this(new DocxSchedulePermitParser());
    }

    @Autowired
    public LegacyDocRevisionPermitParser(DocxSchedulePermitParser wordPermitParser) {
        this.wordPermitParser = wordPermitParser;
    }

    public SchedulePermit parse(Path file, String fileName) {
        return wordPermitParser.parse(file, fileName);
    }

    public WordPermitParseResult parseWithDiagnostics(Path file, String fileName) {
        return wordPermitParser.parseWithDiagnostics(file, fileName);
    }

    SchedulePermit parseContent(String rawContent,
                                List<List<List<String>>> tables,
                                String fileName) {
        String tableText = tables.stream()
                .flatMap(List::stream)
                .flatMap(List::stream)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n"));
        String combined = tableText.isBlank() ? rawContent : rawContent + "\n" + tableText;
        return wordPermitParser.parse(
                new WordPermitDocument(
                        rawContent, tableText, combined, tables,
                        java.util.Collections.nCopies(tables.size(), "")),
                fileName);
    }
}
