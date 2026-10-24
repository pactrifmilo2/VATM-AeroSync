package vatm.aerosync.worker.pipeline;

import java.util.Objects;

final class PermitIdentityText {

    private static final String NUMERO_PREFIX = "\\x{2116}\\s*[:.]?\\s*";

    private PermitIdentityText() {
    }

    static String normalize(String value) {
        return Objects.requireNonNullElse(value, "")
                .replaceAll("(?iu)" + NUMERO_PREFIX, "");
    }
}
