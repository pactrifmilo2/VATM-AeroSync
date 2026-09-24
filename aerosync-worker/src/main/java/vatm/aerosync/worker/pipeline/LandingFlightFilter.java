package vatm.aerosync.worker.pipeline;

import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.SchedulePermit;

final class LandingFlightFilter {
    private LandingFlightFilter() {}

    static SchedulePermit filter(SchedulePermit permit) {
        if (!"LD".equalsIgnoreCase(permit.permitType()) || permit.seasonalPermit()
                || permit.referencedSeasonNewPermit()) return permit;
        var domestic = permit.flights().stream().filter(flight ->
                flight.fromAirport() != null && flight.toAirport() != null
                && (flight.fromAirport().startsWith("VV") || flight.toAirport().startsWith("VV")))
                .toList();
        if (domestic.isEmpty()) {
            throw new BusinessRuleException("BR-LD-NO-VN-AIRPORT",
                    "Landing permit contains no flight with a Viet Nam airport");
        }
        return permit.withFlights(domestic);
    }
}
