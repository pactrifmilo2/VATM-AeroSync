package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class BusinessRuleValidatorStep {

    static final Pattern CALLSIGN_PATTERN = Pattern.compile("^[A-Z0-9]{2,10}$");
    static final Pattern AIRPORT_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final LocalDate minDate = LocalDate.of(2000, 1, 1);

    public void validate(ProcessingContext context) {
        List<RowValidationError> errors = new ArrayList<>();
        int rowNum = 0;
        for (FlightRow row : context.getRows()) {
            rowNum++;
            validateCallsign(row.getCallsign(), rowNum, errors);
            validateAirport(row.getFrom(), "from", "FROM", rowNum, errors);
            validateAirport(row.getTo(), "to", "TO", rowNum, errors);
            validateDateFlight(row.getDateFlight(), rowNum, errors);
            if (Objects.equals(row.getFrom(), row.getTo())) {
                errors.add(error(rowNum, "route", "BR-FROM-TO",
                        "Departure and arrival airports must differ", row.getFrom() + " -> " + row.getTo()));
            }
        }
        if (!errors.isEmpty()) {
            context.getRowValidationErrors().addAll(errors);
            RowValidationError first = errors.getFirst();
            throw new BusinessRuleException(first.code(),
                    "Row %d: %s (%d total validation errors)"
                            .formatted(first.rowNumber(), first.message(), errors.size()),
                    errors);
        }
    }

    private void validateCallsign(String callsign, int rowNum, List<RowValidationError> errors) {
        if (callsign == null || callsign.isBlank()) {
            errors.add(error(rowNum, "callsign", "BR-CALLSIGN", "Callsign is required", callsign));
            return;
        }
        if (!CALLSIGN_PATTERN.matcher(callsign).matches()) {
            errors.add(error(rowNum, "callsign", "BR-CALLSIGN", "Invalid callsign", callsign));
        }
    }

    private void validateAirport(String airport,
                                 String responseField,
                                 String ruleField,
                                 int rowNum,
                                 List<RowValidationError> errors) {
        if (airport == null || !AIRPORT_PATTERN.matcher(airport).matches()) {
            errors.add(error(rowNum, responseField, "BR-" + ruleField, "Invalid " + ruleField + " airport", airport));
        }
    }

    private void validateDateFlight(LocalDate dateFlight, int rowNum, List<RowValidationError> errors) {
        if (dateFlight == null) {
            errors.add(error(rowNum, "dateFlight", "BR-DATEFLIGHT", "dateFlight is required", null));
            return;
        }
        if (dateFlight.isBefore(minDate)) {
            errors.add(error(rowNum, "dateFlight", "BR-DATEFLIGHT", "dateFlight is too far in the past",
                    dateFlight.toString()));
        }
        if (dateFlight.isAfter(LocalDate.now().plusYears(1))) {
            errors.add(error(rowNum, "dateFlight", "BR-DATEFLIGHT", "dateFlight is too far in the future",
                    dateFlight.toString()));
        }
    }

    private RowValidationError error(int rowNum, String field, String code, String message, String value) {
        return new RowValidationError(rowNum, field, code, message, value);
    }
}
