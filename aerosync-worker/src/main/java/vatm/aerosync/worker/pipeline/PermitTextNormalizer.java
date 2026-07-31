package vatm.aerosync.worker.pipeline;

import java.text.Normalizer;
import java.util.Locale;

public final class PermitTextNormalizer {

    private PermitTextNormalizer() {
    }

    public static String clean(String value) {
        return value == null ? "" : value
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String canonical(String value) {
        String folded = Normalizer.normalize(
                        clean(value).replace('Đ', 'D').replace('đ', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static String canonicalHeader(String value) {
        return canonical(value).replaceFirst("\\d+$", "");
    }
}
