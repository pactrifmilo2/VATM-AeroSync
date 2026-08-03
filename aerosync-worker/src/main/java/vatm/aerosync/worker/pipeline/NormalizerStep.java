package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.ZoneId;
import java.util.List;

@Component
public class NormalizerStep {

    private final ZoneId zoneId;
    private final PermitOperatorCatalog permitOperatorCatalog;

    @Autowired
    public NormalizerStep(PermitOperatorCatalog permitOperatorCatalog) {
        this(ZoneId.systemDefault(), permitOperatorCatalog);
    }

    NormalizerStep(ZoneId zoneId) {
        this(zoneId, new PermitOperatorCatalog());
    }

    NormalizerStep(ZoneId zoneId, PermitOperatorCatalog permitOperatorCatalog) {
        this.zoneId = zoneId;
        this.permitOperatorCatalog = permitOperatorCatalog;
    }

    public void normalize(ProcessingContext context) {
        for (FlightRow row : context.getRows()) {
            if (row.getCallsign() != null) {
                row.setCallsign(row.getCallsign().trim().toUpperCase());
            }
            if (row.getFrom() != null) {
                row.setFrom(row.getFrom().trim().toUpperCase());
            }
            if (row.getTo() != null) {
                row.setTo(row.getTo().trim().toUpperCase());
            }
            if (row.getDateFlight() != null) {
                row.setDateFlight(row.getDateFlight().atStartOfDay(zoneId).toLocalDate());
            }
        }

        SchedulePermit permit = context.getSchedulePermit();
        if (permit != null) {
            List<ScheduleFlight> normalizedFlights = permit.flights().stream()
                    .map(flight -> flight.withFlightNumber(
                            permitOperatorCatalog.normalizeFlightNumber(
                                    flight.flightNumber(), permit.operatorId())))
                    .toList();
            List<ScheduleFlight> normalizedOriginalFlights = permit.originalFlights().stream()
                    .map(flight -> flight.withFlightNumber(
                            permitOperatorCatalog.normalizeFlightNumber(
                                    flight.flightNumber(), permit.operatorId())))
                    .toList();
            context.setSchedulePermit(permit.withFlightsAndOriginals(
                    normalizedFlights, normalizedOriginalFlights));
        }
    }
}
