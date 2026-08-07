package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class NormalizerStep {

    private static final Pattern IATA_LABEL = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|M[ÃA]\\s*IATA)(?:\\s*\\([^)]*\\))?\\s*:");
    private static final Pattern ICAO_LABEL = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|M[ÃA]\\s*ICAO)(?:\\s*\\([^)]*\\))?\\s*:");
    private static final Pattern IATA_UNAVAILABLE = Pattern.compile(
            "(?iu)(?:IATA\\s*(?:CODE)?|M[ÃA]\\s*IATA)(?:\\s*\\([^)]*\\))?"
                    + "\\s*:\\s*(?:N\\s*/\\s*A|NA)\\b");
    private static final Pattern ICAO_UNAVAILABLE = Pattern.compile(
            "(?iu)(?:ICAO\\s*(?:CODE)?|M[ÃA]\\s*ICAO)(?:\\s*\\([^)]*\\))?"
                    + "\\s*:\\s*(?:N\\s*/\\s*A|NA)\\b");

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
            boolean preservePrivateCallsign = preservePrivateCallsign(permit);
            List<ScheduleFlight> normalizedFlights = permit.flights().stream()
                    .map(flight -> flight.withFlightNumber(
                            normalizePermitFlightNumber(
                                    flight.flightNumber(), permit.operatorId(),
                                    preservePrivateCallsign)))
                    .toList();
            List<ScheduleFlight> normalizedOriginalFlights = permit.originalFlights().stream()
                    .map(flight -> flight.withFlightNumber(
                            normalizePermitFlightNumber(
                                    flight.flightNumber(), permit.operatorId(),
                                    preservePrivateCallsign)))
                    .toList();
            context.setSchedulePermit(permit.withFlightsAndOriginals(
                    normalizedFlights, normalizedOriginalFlights));
        }
    }

    private String normalizePermitFlightNumber(String flightNumber,
                                               String operatorId,
                                               boolean preservePrivateCallsign) {
        return preservePrivateCallsign
                ? flightNumber
                : permitOperatorCatalog.normalizeFlightNumber(flightNumber, operatorId);
    }

    /**
     * PRV is only a database operator placeholder when the Word document does not
     * provide usable IATA/ICAO codes. In that case the callsign in the document is
     * authoritative and must not have its two-character prefix rewritten to PRV.
     */
    private boolean preservePrivateCallsign(SchedulePermit permit) {
        if (!"PRV".equalsIgnoreCase(permit.operatorId())) {
            return false;
        }
        String content = permit.rawContent() == null
                ? ""
                : permit.rawContent().toUpperCase(Locale.ROOT);
        boolean hasIataLabel = IATA_LABEL.matcher(content).find();
        boolean hasIcaoLabel = ICAO_LABEL.matcher(content).find();
        if (!hasIataLabel && !hasIcaoLabel) {
            return true;
        }
        boolean iataUnavailable = !hasIataLabel || IATA_UNAVAILABLE.matcher(content).find();
        boolean icaoUnavailable = !hasIcaoLabel || ICAO_UNAVAILABLE.matcher(content).find();
        return iataUnavailable && icaoUnavailable;
    }
}
