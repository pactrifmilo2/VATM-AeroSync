package vatm.aerosync.worker.pipeline;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
class WordPermitDocumentReader {

    WordPermitDocument read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return readDocx(file);
        }
        if (name.endsWith(".doc")) {
            return readDoc(file);
        }
        throw new IOException("Unsupported Word document extension: " + name);
    }

    private WordPermitDocument readDocx(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(input)) {
            List<String> paragraphs = new ArrayList<>();
            List<String> pendingContext = new ArrayList<>();
            List<List<List<String>>> tables = new ArrayList<>();
            List<String> tableContexts = new ArrayList<>();
            document.getBodyElements().forEach(element -> {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = clean(paragraph.getText());
                    if (!text.isBlank()) {
                        paragraphs.add(text);
                        pendingContext.add(text);
                    }
                } else if (element instanceof XWPFTable table) {
                    tables.add(docxTableRows(table));
                    tableContexts.add(String.join("\n", pendingContext));
                    pendingContext.clear();
                }
            });
            Date created = document.getProperties()
                    .getCoreProperties()
                    .getCreated();
            LocalDate authoredDate = created == null
                    ? null
                    : created.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            return document(String.join("\n", paragraphs), tables, tableContexts, authoredDate);
        }
    }

    private WordPermitDocument readDoc(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             HWPFDocument document = new HWPFDocument(input)) {
            Range range = document.getRange();
            List<List<List<String>>> tables = new ArrayList<>();
            TableIterator iterator = new TableIterator(range);
            while (iterator.hasNext()) {
                tables.add(docTableRows(iterator.next()));
            }
            Date created = document.getSummaryInformation() == null
                    ? null
                    : document.getSummaryInformation().getCreateDateTime();
            return document(
                    cleanMultiline(range.text()), tables,
                    java.util.Collections.nCopies(tables.size(), ""),
                    created == null
                            ? null
                            : created.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
        }
    }

    private WordPermitDocument document(String paragraphText,
                                        List<List<List<String>>> tables,
                                        List<String> tableContexts,
                                        LocalDate authoredDate) {
        String tableText = tables.stream()
                .flatMap(List::stream)
                .flatMap(List::stream)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
        String rawContent = paragraphText.isBlank()
                ? tableText
                : tableText.isBlank() ? paragraphText : paragraphText + "\n" + tableText;
        return new WordPermitDocument(
                paragraphText, tableText, rawContent, tables, tableContexts, authoredDate);
    }

    private List<List<String>> docxTableRows(XWPFTable table) {
        return table.getRows().stream()
                .map(this::docxRowCells)
                .toList();
    }

    private List<String> docxRowCells(XWPFTableRow row) {
        return row.getTableCells().stream()
                .map(XWPFTableCell::getText)
                .map(this::clean)
                .toList();
    }

    private List<List<String>> docTableRows(Table table) {
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
            TableRow row = table.getRow(rowIndex);
            List<String> cells = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                TableCell cell = row.getCell(cellIndex);
                cells.add(clean(cell.text()));
            }
            rows.add(cells);
        }
        return rows;
    }

    private String clean(String value) {
        return value == null ? "" : value
                .replace('\u0007', ' ')
                .replace('\uFFFD', ' ')
                .replace('\u00a0', ' ')
                .replaceAll("[\\r\\n\\u000B]+", " ")
                .replaceAll("[\\p{Cc}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanMultiline(String value) {
        return value == null ? "" : value
                .replace('\u0007', ' ')
                .replace('\uFFFD', ' ')
                .replace('\u00a0', ' ')
                .replaceAll("[\\r\\n\\u000B]+", "\n")
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n+", "\n")
                .trim();
    }
}
