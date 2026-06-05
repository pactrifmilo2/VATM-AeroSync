package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;

import java.time.LocalDate;

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

    private ProcessingContext contextWith(FlightRow row) {
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, "/tmp/f.csv", "h", FileSourceType.FILESYSTEM, false));
        context.getRows().add(row);
        return context;
    }
}
