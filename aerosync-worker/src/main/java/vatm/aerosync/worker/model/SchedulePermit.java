package vatm.aerosync.worker.model;

import java.time.LocalDate;
import java.util.List;

public record SchedulePermit(
        String sourcePermitNumber,
        String normalizedPermitId,
        String permitNumber,
        String authorId,
        String permitType,
        String version,
        String season,
        LocalDate permitDate,
        String operatorId,
        String reference,
        int validHours,
        String billingAddress,
        String flightType,
        boolean iataAirportsAllowed,
        boolean emptyAirwaysAllowed,
        boolean reviewOnly,
        String rawContent,
        List<ScheduleFlight> flights
) {
    public SchedulePermit {
        flights = List.copyOf(flights);
    }

    public SchedulePermit withFlights(List<ScheduleFlight> resolvedFlights) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                resolvedFlights);
    }

    /**
     * Compatibility constructor for existing programmatic callers. Word profile
     * parsing always supplies the validation policy explicitly.
     */
    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          String rawContent,
                          List<ScheduleFlight> flights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, "LD".equals(permitType), "LD".equals(permitType), false,
                rawContent, flights);
    }

    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          boolean iataAirportsAllowed,
                          boolean emptyAirwaysAllowed,
                          String rawContent,
                          List<ScheduleFlight> flights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, false, rawContent, flights);
    }
}
