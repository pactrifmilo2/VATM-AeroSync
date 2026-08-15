package vatm.aerosync.worker.pipeline;

import java.util.Optional;

@FunctionalInterface
public interface PermitOperatorResolver {

    Optional<String> resolve(String iataCode, String carrierName);

    default Optional<String> resolveFromCallsign(String callsign, String carrierName) {
        if (callsign == null) {
            return Optional.empty();
        }
        String compact = callsign.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        return compact.length() >= 2
                ? resolve(compact.substring(0, 2), carrierName)
                : Optional.empty();
    }

    default boolean hasIata(String icaoCode) {
        return icaoCode != null && !"PRV".equalsIgnoreCase(icaoCode);
    }
}
