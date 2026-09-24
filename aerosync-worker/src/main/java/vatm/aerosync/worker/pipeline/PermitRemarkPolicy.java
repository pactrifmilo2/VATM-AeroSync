package vatm.aerosync.worker.pipeline;

import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class PermitRemarkPolicy {
    private static final Set<String> SPECIAL = Set.of("CAR", "FER", "TECH", "AMB", "VIP");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final Pattern SEASON_SUFFIX = Pattern.compile("(?i)^(.*?/[SW])(?:/.*)?$");

    private PermitRemarkPolicy() {}

    static String newFlight(ScheduleFlight flight) {
        return join(special(flight), DatabaseWriterStep.newPermitRemark(flight.sourceAircraftType()));
    }

    static String suddenRevision(ScheduleFlight flight, LocalDate receivedDate, int revision) {
        return join(special(flight), "EM", DATE.format(receivedDate), "R(" + revision + ")",
                DatabaseWriterStep.newPermitRemark(flight.sourceAircraftType()));
    }

    static String seasonalOld(ScheduleFlight flight, String originalPermit) {
        return join("HUY", "REF", seasonalId(originalPermit),
                DatabaseWriterStep.newPermitRemark(flight.sourceAircraftType()));
    }

    static String seasonalNew(ScheduleFlight flight, String newPermit, String originalPermit) {
        return join(special(flight), "EM", "LAY", seasonalId(newPermit), "REF",
                seasonalId(originalPermit), DatabaseWriterStep.newPermitRemark(flight.sourceAircraftType()));
    }

    static String seasonalId(String value) {
        if (value == null) return null;
        var matcher = SEASON_SUFFIX.matcher(value.trim());
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : value.trim();
    }

    private static String special(ScheduleFlight flight) {
        String code = flight.purposeId() == null ? "" : flight.purposeId().trim().toUpperCase(Locale.ROOT);
        return SPECIAL.contains(code) ? code : null;
    }

    private static String join(String... parts) {
        String result = java.util.Arrays.stream(parts).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        return result.isBlank() ? null : result;
    }
}
