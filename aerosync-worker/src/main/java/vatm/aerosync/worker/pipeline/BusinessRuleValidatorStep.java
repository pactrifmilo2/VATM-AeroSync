package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;

import java.time.LocalDate;
import java.util.regex.Pattern;

@Component
public class BusinessRuleValidatorStep {

    static final Pattern CALLSIGN_PATTERN = Pattern.compile("^[A-Z0-9]{2,10}$");
    static final Pattern AIRPORT_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final LocalDate minDate = LocalDate.of(2000, 1, 1);

    public void validate(ProcessingContext context) {
        int rowNum = 0;
        for (FlightRow row : context.getRows()) {
            rowNum++;
            validateCallsign(row.getCallsign(), rowNum);
            validateAirport(row.getFrom(), "FROM", rowNum);
            validateAirport(row.getTo(), "TO", rowNum);
            validateDateFlight(row.getDateFlight(), rowNum);
            if (row.getFrom().equals(row.getTo())) {
                throw new BusinessRuleException("BR-FROM-TO",
                        "Row %d: departure and arrival airports must differ".formatted(rowNum));
            }
        }
    }

    private void validateCallsign(String callsign, int rowNum) {
        if (callsign == null || callsign.isBlank()) {
            throw new BusinessRuleException("BR-CALLSIGN",
                    "Row %d: callsign is required".formatted(rowNum));
        }
        if (!CALLSIGN_PATTERN.matcher(callsign).matches()) {
            throw new BusinessRuleException("BR-CALLSIGN",
                    "Row %d: invalid callsign '%s'".formatted(rowNum, callsign));
        }
    }

    private void validateAirport(String airport, String field, int rowNum) {
        if (airport == null || !AIRPORT_PATTERN.matcher(airport).matches()) {
            throw new BusinessRuleException("BR-" + field,
                    "Row %d: invalid %s airport '%s'".formatted(rowNum, field, airport));
        }
    }

    private void validateDateFlight(LocalDate dateFlight, int rowNum) {
        if (dateFlight == null) {
            throw new BusinessRuleException("BR-DATEFLIGHT",
                    "Row %d: dateFlight is required".formatted(rowNum));
        }
        if (dateFlight.isBefore(minDate)) {
            throw new BusinessRuleException("BR-DATEFLIGHT",
                    "Row %d: dateFlight is too far in the past".formatted(rowNum));
        }
        if (dateFlight.isAfter(LocalDate.now().plusYears(1))) {
            throw new BusinessRuleException("BR-DATEFLIGHT",
                    "Row %d: dateFlight is too far in the future".formatted(rowNum));
        }
    }
}
