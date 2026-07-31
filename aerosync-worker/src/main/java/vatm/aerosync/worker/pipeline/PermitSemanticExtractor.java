package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class PermitSemanticExtractor {

    private static final Pattern PERMIT_IDENTITY = Pattern.compile(
            "(?iu)\\b(?:LD|O\\s*/\\s*F|OF)\\s*-\\s*\\d{1,5}"
                    + "(?:\\s*/\\s*[A-Z0-9]{1,8}){2,3}"
                    + "(?:\\s*[-/]\\s*REV\\s*\\d+)?\\b");
    private static final Pattern DATE = Pattern.compile(
            "(?iu)(?<date>"
                    + "(?<!\\d)\\d{1,2}/\\d{1,2}/20\\d{2}(?!\\d)"
                    + "|(?<![A-Z0-9])\\d{1,2}-[A-Z]{3}-\\d{2,4}(?![A-Z0-9])"
                    + "|(?<![A-Z0-9])\\d{1,2}\\s+[A-Z]{3}\\s+20\\d{2}(?![A-Z0-9])"
                    + "|(?<![A-Z0-9])\\d{1,2}[A-Z]{3}20\\d{2}(?![A-Z0-9])"
                    + ")");
    private static final Pattern ICAO_LABEL = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|MA\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "\\s*:\\s*(?<value>[A-Z0-9]{3})(?![A-Z0-9])");
    private static final Pattern IATA_LABEL = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|MA\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "\\s*:\\s*(?<value>[A-Z0-9]{2})(?![A-Z0-9])");
    private static final Pattern ADDRESS_LABEL = Pattern.compile(
            "(?iu)(?:POSTAL\\s+ADDRESS|ĐỊA\\s+CHỈ(?:\\s+BƯU\\s+ĐIỆN)?|ADDRESS)"
                    + "\\s*:\\s*(?<value>.+)");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            formatter("d/M/uuuu", Locale.ENGLISH),
            formatter("d-MMM-uu", Locale.ENGLISH),
            formatter("d-MMM-uuuu", Locale.ENGLISH),
            formatter("d MMM uuuu", Locale.ENGLISH),
            formatter("dMMMuuuu", Locale.ENGLISH));

    PermitSemanticEvidence extract(WordPermitDocument document) {
        return new PermitSemanticEvidence(
                permitIdentities(document),
                permitDate(document),
                labeledCode(document.rawContent(), ICAO_LABEL, 0.98, "ICAO_LABEL"),
                labeledCode(document.rawContent(), IATA_LABEL, 0.98, "IATA_LABEL"),
                billingAddress(document),
                tableRoles(document.tableContexts()));
    }

    private List<PermitSemanticEvidence.PermitIdentityCandidate> permitIdentities(
            WordPermitDocument document) {
        Map<String, PermitSemanticEvidence.PermitIdentityCandidate> candidates =
                new LinkedHashMap<>();
        String[] paragraphLines = document.paragraphText().split("\\R");
        for (int lineIndex = 0; lineIndex < paragraphLines.length; lineIndex++) {
            String line = paragraphLines[lineIndex];
            Matcher matcher = PERMIT_IDENTITY.matcher(line);
            while (matcher.find()) {
                String raw = PermitTextNormalizer.clean(matcher.group());
                String canonical = canonicalPermit(raw);
                double confidence = lineIndex < 6 ? 0.99 : 0.90;
                if (fold(line).contains("REF")) {
                    confidence = Math.min(confidence, 0.85);
                }
                putBetter(candidates, new PermitSemanticEvidence.PermitIdentityCandidate(
                        raw,
                        canonical,
                        "PARAGRAPH_LINE[" + (lineIndex + 1) + "]",
                        confidence));
            }
        }

        for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
            List<List<String>> table = document.tables().get(tableIndex);
            for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
                List<String> row = table.get(rowIndex);
                for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                    Matcher matcher = PERMIT_IDENTITY.matcher(row.get(columnIndex));
                    while (matcher.find()) {
                        String raw = PermitTextNormalizer.clean(matcher.group());
                        putBetter(candidates, new PermitSemanticEvidence.PermitIdentityCandidate(
                                raw,
                                canonicalPermit(raw),
                                "TABLE[" + (tableIndex + 1)
                                        + "].ROW[" + (rowIndex + 1)
                                        + "].COLUMN[" + (columnIndex + 1) + "]",
                                0.70));
                    }
                }
            }
        }
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(
                                PermitSemanticEvidence.PermitIdentityCandidate::confidence)
                        .reversed())
                .toList();
    }

    private void putBetter(
            Map<String, PermitSemanticEvidence.PermitIdentityCandidate> candidates,
            PermitSemanticEvidence.PermitIdentityCandidate candidate) {
        candidates.merge(
                candidate.canonicalValue(),
                candidate,
                (current, replacement) ->
                        replacement.confidence() > current.confidence() ? replacement : current);
    }

    private PermitSemanticEvidence.SemanticValue<LocalDate> permitDate(
            WordPermitDocument document) {
        List<PermitSemanticEvidence.SemanticValue<LocalDate>> candidates = new ArrayList<>();
        String[] lines = document.paragraphText().split("\\R");
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            Matcher matcher = DATE.matcher(line);
            while (matcher.find()) {
                LocalDate parsed = parseDate(matcher.group("date"));
                if (parsed == null) {
                    continue;
                }
                String folded = fold(line);
                boolean labeled = folded.contains("HANOI")
                        || folded.contains("HA NOI")
                        || folded.contains("NGAY")
                        || folded.contains("DATE");
                double confidence = labeled ? 0.99 : lineIndex < 6 ? 0.94 : 0.80;
                candidates.add(new PermitSemanticEvidence.SemanticValue<>(
                        parsed,
                        "PARAGRAPH_LINE[" + (lineIndex + 1) + "]",
                        confidence,
                        labeled ? "DATE_NEAR_LABEL" : "PARAGRAPH_DATE"));
            }
        }
        return candidates.stream()
                .max(Comparator.comparingDouble(
                        PermitSemanticEvidence.SemanticValue<LocalDate>::confidence))
                .orElseGet(() -> document.authoredDate() == null
                        ? null
                        : new PermitSemanticEvidence.SemanticValue<>(
                                document.authoredDate(),
                                "DOCUMENT_METADATA",
                                0.65,
                                "DOCUMENT_CREATED_DATE"));
    }

    private PermitSemanticEvidence.SemanticValue<String> labeledCode(
            String content,
            Pattern pattern,
            double confidence,
            String method) {
        Matcher matcher = pattern.matcher(fold(content));
        if (!matcher.find()) {
            return null;
        }
        return new PermitSemanticEvidence.SemanticValue<>(
                matcher.group("value").toUpperCase(Locale.ROOT),
                "RAW",
                confidence,
                method);
    }

    private PermitSemanticEvidence.SemanticValue<String> billingAddress(
            WordPermitDocument document) {
        for (int tableIndex = 0; tableIndex < document.tables().size(); tableIndex++) {
            List<List<String>> table = document.tables().get(tableIndex);
            for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
                List<String> row = table.get(rowIndex);
                for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                    Matcher matcher = ADDRESS_LABEL.matcher(row.get(columnIndex));
                    if (matcher.find()) {
                        return new PermitSemanticEvidence.SemanticValue<>(
                                PermitTextNormalizer.clean(matcher.group("value")),
                                "TABLE[" + (tableIndex + 1)
                                        + "].ROW[" + (rowIndex + 1)
                                        + "].COLUMN[" + (columnIndex + 1) + "]",
                                0.98,
                                "ADDRESS_LABEL");
                    }
                }
            }
        }
        String[] lines = document.paragraphText().split("\\R");
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            Matcher matcher = ADDRESS_LABEL.matcher(lines[lineIndex]);
            if (matcher.find()) {
                return new PermitSemanticEvidence.SemanticValue<>(
                        PermitTextNormalizer.clean(matcher.group("value")),
                        "PARAGRAPH_LINE[" + (lineIndex + 1) + "]",
                        0.95,
                        "ADDRESS_LABEL");
            }
        }
        return null;
    }

    private Map<Integer, PermitSemanticEvidence.TableRoleEvidence> tableRoles(
            List<String> contexts) {
        Map<Integer, PermitSemanticEvidence.TableRoleEvidence> roles = new LinkedHashMap<>();
        for (int tableIndex = 0; tableIndex < contexts.size(); tableIndex++) {
            String folded = fold(contexts.get(tableIndex));
            PermitSemanticEvidence.TableRole role = null;
            double confidence = 0.0;
            if (folded.matches("(?s).*\\b2\\s*\\.\\s*5\\b.*")
                    || folded.contains("TRANSFER FLIGHT")
                    || folded.contains("CHUYEN SAN")) {
                role = PermitSemanticEvidence.TableRole.SUPPLEMENTAL;
                confidence = folded.matches("(?s).*\\b2\\s*\\.\\s*5\\b.*") ? 0.99 : 0.95;
            } else if (folded.matches("(?s).*\\b2\\s*\\.\\s*2\\b.*")
                    || folded.contains("NEW SCHEDULE")
                    || folded.contains("REPLACEMENT SCHEDULE")
                    || folded.contains("LICH BAY MOI")
                    || folded.contains("SUA DOI")) {
                role = PermitSemanticEvidence.TableRole.REPLACEMENT;
                confidence = folded.matches("(?s).*\\b2\\s*\\.\\s*2\\b.*") ? 0.99 : 0.95;
            } else if (folded.matches("(?s).*\\b2\\s*\\.\\s*1\\b.*")
                    || folded.contains("ORIGINAL SCHEDULE")
                    || folded.contains("OLD SCHEDULE")
                    || folded.contains("LICH BAY GOC")
                    || folded.contains("LICH BAY CU")) {
                role = PermitSemanticEvidence.TableRole.ORIGINAL;
                confidence = folded.matches("(?s).*\\b2\\s*\\.\\s*1\\b.*") ? 0.99 : 0.95;
            }
            if (role != null) {
                roles.put(tableIndex, new PermitSemanticEvidence.TableRoleEvidence(
                        role,
                        "TABLE[" + (tableIndex + 1) + "].CONTEXT",
                        confidence));
            }
        }
        return roles;
    }

    private String canonicalPermit(String value) {
        return PermitTextNormalizer.clean(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private LocalDate parseDate(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next shared date representation.
            }
        }
        return null;
    }

    private String fold(String value) {
        return Normalizer.normalize(
                        PermitTextNormalizer.clean(value)
                                .replace('Đ', 'D')
                                .replace('đ', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
    }

    private static DateTimeFormatter formatter(String pattern, Locale locale) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(locale);
    }
}
