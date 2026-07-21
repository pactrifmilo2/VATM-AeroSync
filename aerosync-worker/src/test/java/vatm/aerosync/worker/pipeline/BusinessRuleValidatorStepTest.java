package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BusinessRuleValidatorStepTest {

    private BusinessRuleValidatorStep validator;

    @BeforeEach
    void setUp() {
        validator = new BusinessRuleValidatorStep();
    }

    @Test
    void validate_acceptsValidRow() {
        ProcessingContext context = contextWith(new FlightRow("VN123", "HAN", "SGN", LocalDate.of(2026, 6, 1)));

        assertDoesNotThrow(() -> validator.validate(context));
    }

    @Test
    void validate_rejectsInvalidCallsign() {
        ProcessingContext context = contextWith(new FlightRow("!", "HAN", "SGN", LocalDate.of(2026, 6, 1)));

        assertThatThrownBy(() -> validator.validate(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-CALLSIGN");
    }

    @Test
    void validate_rejectsSameFromAndTo() {
        ProcessingContext context = contextWith(new FlightRow("VN123", "HAN", "HAN", LocalDate.of(2026, 6, 1)));

        assertThatThrownBy(() -> validator.validate(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-FROM-TO");
    }

    @Test
    void validate_rejectsFutureDateBeyondOneYear() {
        ProcessingContext context = contextWith(
                new FlightRow("VN123", "HAN", "SGN", LocalDate.now().plusYears(2)));

        assertThatThrownBy(() -> validator.validate(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-DATEFLIGHT");
    }

    @Test
    void validate_collectsAllRowErrorsBeforeThrowing() {
        ProcessingContext context = contextWith(
                new FlightRow("!", "HAN", "HAN", LocalDate.now().plusYears(2)));

        Throwable thrown = catchThrowable(() -> validator.validate(context));

        org.assertj.core.api.Assertions.assertThat(thrown)
                .isInstanceOf(BusinessRuleException.class);
        BusinessRuleException exception = (BusinessRuleException) thrown;
        org.assertj.core.api.Assertions.assertThat(exception.getRowErrors())
                .extracting("rowNumber", "field", "code")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "callsign", "BR-CALLSIGN"),
                        org.assertj.core.groups.Tuple.tuple(1, "dateFlight", "BR-DATEFLIGHT"),
                        org.assertj.core.groups.Tuple.tuple(1, "route", "BR-FROM-TO"));
        org.assertj.core.api.Assertions.assertThat(context.getRowValidationErrors())
                .hasSameSizeAs(exception.getRowErrors());
    }

    @Test
    void validate_acceptsMappedScheduledPermit() {
        ProcessingContext context = scheduleContext("1000000", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27));

        assertDoesNotThrow(() -> validator.validate(context));
    }

    @Test
    void validate_rejectsScheduleWithoutOperatingDateInRange() {
        ProcessingContext context = scheduleContext("0200000", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 20));

        assertThatThrownBy(() -> validator.validate(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-SERVICE-DAYS-RANGE");
    }

    private ProcessingContext contextWith(FlightRow row) {
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, "/tmp/f.csv", "h", FileSourceType.FILESYSTEM, false));
        context.getRows().add(row);
        return context;
    }

    private ProcessingContext scheduleContext(String days, LocalDate begin, LocalDate end) {
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, "/tmp/permit.docx", "h", FileSourceType.EMAIL, false));
        ScheduleFlight flight = new ScheduleFlight(
                "CAR", 1935L, BigDecimal.ZERO, "RMY685", null, days,
                "WMKK", "VHHH", "1140", null, "M765/M771",
                begin, end, "CAR 76X/32X");
        context.setSchedulePermit(new SchedulePermit(
                "OF-5199/7/2026VN", "O/F 05199/S/CHK/2026", "5199",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 7, 17),
                "RMY", "G17.44", 72, "Cyberjaya", "SC", "raw", List.of(flight)));
        return context;
    }
}
