package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class BusinessRuleValidatorStep {

    static final Pattern CALLSIGN_PATTERN = Pattern.compile("^[A-Z0-9]{2,10}$");
    static final Pattern AIRPORT_PATTERN = Pattern.compile("^[A-Z]{3}$");
    static final Pattern ICAO_AIRPORT_PATTERN = Pattern.compile("^[A-Z]{4}$");
    static final Pattern TIME_PATTERN = Pattern.compile("^(?:[01]\\d|2[0-3])[0-5]\\d$");

    private final LocalDate minDate = LocalDate.of(2000, 1, 1);

    public void validate(ProcessingContext context) {
        if (context.getSchedulePermit() != null) {
            validateSchedulePermit(context);
            return;
        }
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

    private void validateSchedulePermit(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        List<RowValidationError> errors = new ArrayList<>();
        if (permit.normalizedPermitId() == null
                || permit.normalizedPermitId().isBlank()
                || permit.normalizedPermitId().length() > 100
                || !permit.normalizedPermitId().matches("^[A-Z0-9][A-Z0-9 /-]+$")) {
            errors.add(error(0, "permitNumber", "BR-PERMIT-ID",
                    "Invalid normalized scheduled permit number", permit.normalizedPermitId()));
        }
        if (permit.permitYear() == null) {
            errors.add(error(0, "permitYear", "BR-PERMIT-YEAR",
                    "Scheduled permit number must include its four-digit year",
                    permit.normalizedPermitId()));
        }
        if (permit.permitDate() == null) {
            errors.add(error(0, "permitDate", "BR-PERMIT-DATE", "Permit date is required", null));
        }
        if (permit.operatorId() == null || !permit.operatorId().matches("^[A-Z0-9]{3}$")) {
            errors.add(error(0, "operator", "BR-OPERATOR", "Invalid operator ICAO code", permit.operatorId()));
        }
        if (permit.permitType() == null || permit.permitType().isBlank()
                || permit.flightType() == null || permit.flightType().isBlank()) {
            errors.add(error(0, "permitType", "BR-SCHEDULE-TYPE",
                    "Permit type and flight type are required",
                    permit.permitType() + "/" + permit.flightType()));
        }
        if (permit.flights().isEmpty()) {
            errors.add(error(0, "flights", "BR-SCHEDULE-EMPTY", "At least one schedule row is required", null));
        }
        for (int index = 0; index < permit.flights().size(); index++) {
            validateScheduleFlight(
                    permit.flights().get(index),
                    index + 1,
                    permit.iataAirportsAllowed(),
                    permit.emptyAirwaysAllowed(),
                    errors);
        }
        if (!errors.isEmpty()) {
            context.getRowValidationErrors().addAll(errors);
            RowValidationError first = errors.getFirst();
            throw new BusinessRuleException(first.code(),
                    "Schedule row %d: %s (%d total validation errors)"
                            .formatted(first.rowNumber(), first.message(), errors.size()),
                    errors);
        }
    }

    private void validateScheduleFlight(ScheduleFlight flight,
                                        int rowNumber,
                                        boolean allowIataAirport,
                                        boolean allowMissingAirways,
                                        List<RowValidationError> errors) {
        if (flight.flightNumber() == null || !flight.flightNumber().matches("^[A-Z0-9]{2,20}$")) {
            errors.add(error(rowNumber, "flightNumber", "BR-FLIGHT-NUMBER",
                    "Invalid flight number", flight.flightNumber()));
        }
        validateScheduleAirport(flight.fromAirport(), "fromAirport", rowNumber, allowIataAirport, errors);
        validateScheduleAirport(flight.toAirport(), "toAirport", rowNumber, allowIataAirport, errors);
        if (Objects.equals(flight.fromAirport(), flight.toAirport())) {
            errors.add(error(rowNumber, "route", "BR-FROM-TO",
                    "Departure and arrival airports must differ",
                    flight.fromAirport() + " -> " + flight.toAirport()));
        }
        if (flight.etd() == null || !TIME_PATTERN.matcher(flight.etd()).matches()) {
            errors.add(error(rowNumber, "etd", "BR-ETD", "Invalid UTC ETD", flight.etd()));
        }
        if (flight.eta() != null && !TIME_PATTERN.matcher(flight.eta().replace("+", "")).matches()) {
            errors.add(error(rowNumber, "eta", "BR-ETA", "Invalid UTC ETA", flight.eta()));
        }
        if (flight.beginDate() == null || flight.endDate() == null
                || flight.beginDate().isAfter(flight.endDate())) {
            errors.add(error(rowNumber, "effectiveDates", "BR-EFFECTIVE-DATES",
                    "Invalid schedule effective date range: begin date must not be after end date",
                    flight.beginDate() + " -> " + flight.endDate()));
        }
        if (!validServiceDays(flight.serviceDays())) {
            errors.add(error(rowNumber, "serviceDays", "BR-SERVICE-DAYS",
                    "Invalid day-of-service flags", flight.serviceDays()));
        } else if (flight.beginDate() != null && flight.endDate() != null
                && !hasOperatingDate(flight)) {
            errors.add(error(rowNumber, "serviceDays", "BR-SERVICE-DAYS-RANGE",
                    "No selected operating day falls in the effective date range", flight.serviceDays()));
        }
        if (!allowMissingAirways && (flight.via() == null || flight.via().isBlank())) {
            errors.add(error(rowNumber, "via", "BR-AIRWAYS", "Airways are required", flight.via()));
        }
        if (flight.craftId() <= 0) {
            errors.add(error(rowNumber, "craftId", "BR-CRAFT", "Aircraft mapping is required",
                    Long.toString(flight.craftId())));
        }
    }

    private void validateScheduleAirport(String airport,
                                         String field,
                                         int rowNumber,
                                         boolean allowIataAirport,
                                         List<RowValidationError> errors) {
        if (airport == null
                || !((allowIataAirport && AIRPORT_PATTERN.matcher(airport).matches())
                || ICAO_AIRPORT_PATTERN.matcher(airport).matches())) {
            errors.add(error(rowNumber, field, "BR-SCHEDULE-AIRPORT", "Invalid schedule airport", airport));
        }
    }

    private void validateIcaoAirport(String airport,
                                     String field,
                                     int rowNumber,
                                     List<RowValidationError> errors) {
        if (airport == null || !ICAO_AIRPORT_PATTERN.matcher(airport).matches()) {
            errors.add(error(rowNumber, field, "BR-ICAO-AIRPORT", "Invalid ICAO airport", airport));
        }
    }

    private boolean validServiceDays(String value) {
        if (value == null || value.length() != 7) {
            return false;
        }
        boolean selected = false;
        for (int index = 0; index < value.length(); index++) {
            char actual = value.charAt(index);
            char expected = (char) ('1' + index);
            if (actual != '0' && actual != expected) {
                return false;
            }
            selected |= actual == expected;
        }
        return selected;
    }

    private boolean hasOperatingDate(ScheduleFlight flight) {
        LocalDate date = flight.beginDate();
        while (!date.isAfter(flight.endDate())) {
            int dayIndex = date.getDayOfWeek().getValue() - 1;
            if (flight.serviceDays().charAt(dayIndex) != '0') {
                return true;
            }
            date = date.plusDays(1);
        }
        return false;
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
