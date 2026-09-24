package vatm.aerosync.worker.model;

import java.util.Objects;
import java.util.Locale;

/** Strict OLD identity inside one NO master; never use callsign alone to delete. */
public final class NoRevisionIdentity {
    private NoRevisionIdentity() {}

    public static boolean matches(ScheduleFlight stored, ScheduleFlight old) {
        return same(stored.flightNumber(), old.flightNumber())
                && same(stored.fromAirport(), old.fromAirport()) && same(stored.toAirport(), old.toAirport())
                && Objects.equals(stored.beginDate(), old.beginDate())
                && Objects.equals(stored.endDate(), old.endDate())
                && same(stored.etd(), old.etd())
                && (old.eta() == null || old.eta().isBlank() || same(stored.eta(), old.eta()));
    }

    public static boolean same(String left, String right) {
        return Objects.equals(normalize(left), normalize(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
