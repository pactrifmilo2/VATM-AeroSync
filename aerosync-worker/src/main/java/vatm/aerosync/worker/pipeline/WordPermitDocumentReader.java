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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
class WordPermitDocumentReader {

    private static final Pattern HYPHENATED_INLINE_SCHEDULE = Pattern.compile(
            "(?imu)^\\s*(?<flight>[A-Z0-9]{3,10})\\s*-\\s*"
                    + "(?<date>\\d{1,2}[A-Z]{3}\\d{2})\\s*-\\s*"
                    + "(?<from>[A-Z]{4})\\s*-\\s*(?<etd>\\d{4})Z?\\s*-\\s*"
                    + "(?<to>[A-Z]{4})\\s*-\\s*(?<eta>\\d{4})Z?\\s*-\\s*"
                    + "(?<aircraft>[A-Z0-9-]+)\\s*$");
    private static final Pattern SPACED_INLINE_SCHEDULE = Pattern.compile(
            "(?iu)(?<flight>[A-Z0-9]{3,10})\\s+"
                    + "(?<date>\\d{1,2}[A-Z]{3}\\d{2})\\s+"
                    + "(?<days>[1-7-]{1,7})\\s+"
                    + "(?<from>[A-Z]{4})\\s+(?<etd>\\d{4})Z?\\s+"
                    + "(?<to>[A-Z]{4})\\s+(?<eta>\\d{4})Z?\\s+"
                    + "(?<aircraft>[A-Z0-9-]+)");

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
        List<List<List<String>>> resolvedTables = new ArrayList<>(tables);
        List<String> resolvedContexts = new ArrayList<>(tableContexts);
        if (resolvedTables.isEmpty()) {
            List<List<String>> inlineSchedule = inlineSchedule(paragraphText);
            if (!inlineSchedule.isEmpty()) {
                resolvedTables.add(inlineSchedule);
                resolvedContexts.add(paragraphText);
            }
        }
        String tableText = resolvedTables.stream()
                .flatMap(List::stream)
                .flatMap(List::stream)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
        String rawContent = paragraphText.isBlank()
                ? tableText
                : tableText.isBlank() ? paragraphText : paragraphText + "\n" + tableText;
        return new WordPermitDocument(
                paragraphText, tableText, rawContent,
                List.copyOf(resolvedTables), List.copyOf(resolvedContexts), authoredDate);
    }

    private List<List<String>> inlineSchedule(String paragraphText) {
        Matcher matcher = HYPHENATED_INLINE_SCHEDULE.matcher(paragraphText);
        String days = "1";
        if (!matcher.find()) {
            matcher = SPACED_INLINE_SCHEDULE.matcher(paragraphText);
            if (!matcher.find()) {
                return List.of();
            }
            days = matcher.group("days");
        }
        return List.of(
                List.of(
                        "Flight number", "Effective from", "Effective to",
                        "Days of services", "Departure Airport", "ETD",
                        "Arrival Airport", "ETA", "Aircraft Type"),
                List.of(
                        matcher.group("flight"), matcher.group("date"), matcher.group("date"),
                        days, matcher.group("from"), matcher.group("etd"),
                        matcher.group("to"), matcher.group("eta"), matcher.group("aircraft")));
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
