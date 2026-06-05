package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;

import java.time.ZoneId;

@Component
public class NormalizerStep {

    private final ZoneId zoneId;

    public NormalizerStep() {
        this(ZoneId.systemDefault());
    }

    NormalizerStep(ZoneId zoneId) {
        this.zoneId = zoneId;
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
    }
}
