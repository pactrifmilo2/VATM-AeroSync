package vatm.aerosync.worker.model;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

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
        List<ScheduleFlight> flights,
        List<ScheduleFlight> originalFlights
) {
    private static final Pattern REVISION_MARKER = Pattern.compile(
            "(?iu)(?:\\bREV(?:ISION)?\\s*\\d*\\b|\\bRVS\\s*\\d*\\b|"
                    + "SỬA\\s*ĐỔI|SUA\\s*DOI|LỊCH\\s*BAY\\s*GỐC|LICH\\s*BAY\\s*GOC|"
                    + "ORIGINAL\\s+SCHEDULE)");
    private static final Pattern PERMIT_YEAR = Pattern.compile("(?:19|20)\\d{2}");

    public SchedulePermit {
        flights = List.copyOf(flights);
        originalFlights = originalFlights == null ? List.of() : List.copyOf(originalFlights);
    }

    public SchedulePermit withFlights(List<ScheduleFlight> resolvedFlights) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                resolvedFlights, originalFlights);
    }

    public SchedulePermit withFlightsAndOriginals(List<ScheduleFlight> resolvedFlights,
                                                   List<ScheduleFlight> resolvedOriginalFlights) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                resolvedFlights, resolvedOriginalFlights);
    }

    public boolean revision() {
        return REVISION_MARKER.matcher(
                (sourcePermitNumber == null ? "" : sourcePermitNumber) + "\n"
                        + (rawContent == null ? "" : rawContent)).find();
    }

    public Integer permitYear() {
        if (normalizedPermitId == null) {
            return null;
        }
        java.util.regex.Matcher matcher = PERMIT_YEAR.matcher(normalizedPermitId);
        Integer year = null;
        while (matcher.find()) {
            year = Integer.valueOf(matcher.group());
        }
        return year;
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
                rawContent, flights, List.of());
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
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, false, rawContent, flights,
                List.of());
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
                          boolean reviewOnly,
                          String rawContent,
                          List<ScheduleFlight> flights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                flights, List.of());
    }
}
