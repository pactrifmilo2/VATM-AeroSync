package vatm.aerosync.common.dto;

import java.time.LocalDate;
import java.util.List;

public record PermitReviewSnapshot(
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
        String rawContent,
        List<PermitReviewFlightSnapshot> flights
) {
    public PermitReviewSnapshot {
        flights = flights == null ? List.of() : List.copyOf(flights);
    }
}
