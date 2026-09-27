package vatm.aerosync.api.service;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DatabaseState {
    FOUND, MISSING, ERROR, PENDING, UNKNOWN;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
