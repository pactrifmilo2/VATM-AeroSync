package vatm.aerosync.worker.pipeline;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Scope explicit revision values to NEW/common sections, never OLD text. */
final class RevisionDocumentText {
    private RevisionDocumentText() {}

    static String folded(String value) {
        return Normalizer.normalize(value.replace('Đ', 'D').replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
    }

    static int role(String context) {
        int role = 0;
        for (String line : context.split("\\R")) {
            String value = folded(line).trim();
            if (value.length() > 160) continue;
            if (Pattern.compile("(?:^|\\s)(?:NEW|REVISED|REVISE|REPLACEMENT)\\b|\\b(?:MOI|SUA DOI|THAY DOI)\\b")
                    .matcher(value).find()) role = 1;
            else if (Pattern.compile("(?:^|\\s)(?:OLD|ORIGINAL|ORIGIN)\\b|\\b(?:CU|GOC|BAN DAU)\\b")
                    .matcher(value).find()) role = -1;
        }
        return role;
    }

    static String newValues(String raw) {
        StringBuilder result = new StringBuilder();
        int role = 0;
        boolean purposeSection = false;
        for (String line : (raw == null ? "" : raw).split("\\R")) {
            String folded = folded(line).trim();
            boolean purposeHeading = folded.matches(".*(?:PURPOSE(?: OF FLIGHT(?:\\(S\\)|S)?)?|MUC DICH(?: CHUYEN BAY)?).*?")
                    && !line.contains("\t");
            int explicit = line.contains("\t") ? 0 : role(line);
            if (purposeHeading) purposeSection = true;
            else if (line.matches("^\\s*\\d+\\.\\s+.*")) purposeSection = false;
            if (explicit != 0) role = explicit;
            else if (line.matches("^\\s*\\d+\\.\\s+.*")) role = 0;
            if (role >= 0) {
                // Purpose sections often have "4.2 NEW: VIP" or a standalone
                // value below NEW rather than repeating the full field label.
                if (purposeSection && !purposeHeading && !line.isBlank()) {
                    String value = line.replaceFirst("(?iu)^\\s*\\d+(?:\\.\\d+)*[.)]?\\s*", "")
                            .replaceFirst("(?iu)^(?:NEW|REVISED|MOI|MỚI)\\s*:?[ \\t]*", "").trim();
                    if (!value.isBlank()) result.append("Purpose of Flight: ").append(value).append('\n');
                } else result.append(line).append('\n');
            }
        }
        return result.toString();
    }
}
