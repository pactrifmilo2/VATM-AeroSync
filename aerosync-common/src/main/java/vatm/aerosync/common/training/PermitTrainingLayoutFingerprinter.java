package vatm.aerosync.common.training;

import vatm.aerosync.common.dto.PermitTrainingDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Produces a value-insensitive signature for a captured Word layout. */
public final class PermitTrainingLayoutFingerprinter {

    private static final Pattern DATE = Pattern.compile(
            "(?iu)\\b(?:\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s*[A-Z]{3}\\s*\\d{2,4})\\b");
    private static final Pattern TIME = Pattern.compile("\\b[0-2]\\d[0-5]\\d\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private PermitTrainingLayoutFingerprinter() {
    }

    public static String fingerprint(PermitTrainingDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Training document is required");
        }
        StringBuilder canonical = new StringBuilder("layout-v1|");
        safe(document.paragraphText()).lines()
                .map(PermitTrainingLayoutFingerprinter::labelPart)
                .filter(value -> !value.isBlank())
                .limit(20)
                .forEach(value -> canonical.append("p:").append(value).append('|'));
        for (PermitTrainingDocument.Table table : document.tables()) {
            int width = table.rows().stream()
                    .mapToInt(row -> row.cells().size())
                    .max().orElse(0);
            canonical.append("t:").append(table.index()).append(':')
                    .append(width).append('|');
            table.rows().stream()
                    .filter(row -> row.cells().stream()
                            .anyMatch(cell -> cell.value() != null
                                    && !cell.value().isBlank()))
                    .limit(2)
                    .forEach(row -> {
                        canonical.append("r:").append(row.cells().size()).append(':');
                        row.cells().forEach(cell -> canonical
                                .append(cellSignature(cell.value())).append(';'));
                        canonical.append('|');
                    });
        }
        return sha256(canonical.toString());
    }

    private static String labelPart(String line) {
        String value = safe(line);
        int colon = Math.max(value.indexOf(':'), value.indexOf('\uff1a'));
        if (colon >= 0) {
            return normalize(value.substring(0, colon));
        }
        if (value.matches("^\\s*\\d+(?:\\.\\d+)?\\.?\\s+.*")) {
            return normalize(value.replaceFirst(
                    "^\\s*\\d+(?:\\.\\d+)?\\.?\\s+", ""));
        }
        return "";
    }

    private static String cellSignature(String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) {
            return "";
        }
        if (raw.matches(".*\\d.*")
                && !raw.matches("(?iu).*(?:flight|effective|day|airport|aircraft|permit).*")) {
            return "{value}";
        }
        if (raw.matches("[A-Z0-9/+.\\-]{2,16}")) {
            return "{value}";
        }
        String normalized = normalize(raw);
        if (normalized.length() > 80) {
            return "{value}";
        }
        return normalized;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(
                        safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = DATE.matcher(normalized).replaceAll("{date}");
        normalized = TIME.matcher(normalized).replaceAll("{time}");
        normalized = DIGITS.matcher(normalized).replaceAll("{n}");
        return SPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
