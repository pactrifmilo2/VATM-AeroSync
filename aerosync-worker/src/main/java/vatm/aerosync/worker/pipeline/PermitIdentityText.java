package vatm.aerosync.worker.pipeline;

import java.util.Objects;

final class PermitIdentityText {

    private static final String NUMERO_PREFIX = "\\x{2116}\\s*[:.]?\\s*";

    /**
     * CAAV forms mix hyphens with typographic dashes, e.g. "OF – 37/A/W/2026VN".
     * Fold every dash variant onto ASCII '-' so one profile pattern covers both.
     */
    private static final String DASH_VARIANTS = "[\\x{2010}-\\x{2015}\\x{2212}\\x{FE58}\\x{FE63}]";

    private PermitIdentityText() {
    }

    static String normalize(String value) {
        return Objects.requireNonNullElse(value, "")
                .replaceAll("(?iu)" + NUMERO_PREFIX, "")
                .replaceAll(DASH_VARIANTS, "-");
    }
}
