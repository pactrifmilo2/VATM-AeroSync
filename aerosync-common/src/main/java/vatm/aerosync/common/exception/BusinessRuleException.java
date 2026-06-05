package vatm.aerosync.common.exception;

import vatm.aerosync.common.dto.RowValidationError;

import java.util.List;

public class BusinessRuleException extends RuntimeException {

    private final String ruleCode;
    private final List<RowValidationError> rowErrors;

    public BusinessRuleException(String ruleCode, String message) {
        this(ruleCode, message, List.of());
    }

    public BusinessRuleException(String ruleCode, String message, List<RowValidationError> rowErrors) {
        super("%s: %s".formatted(ruleCode, message));
        this.ruleCode = ruleCode;
        this.rowErrors = List.copyOf(rowErrors);
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public List<RowValidationError> getRowErrors() {
        return rowErrors;
    }
}
