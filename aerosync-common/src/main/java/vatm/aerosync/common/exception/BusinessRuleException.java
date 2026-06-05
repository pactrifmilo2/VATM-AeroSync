package vatm.aerosync.common.exception;

public class BusinessRuleException extends RuntimeException {

    private final String ruleCode;

    public BusinessRuleException(String ruleCode, String message) {
        super("%s: %s".formatted(ruleCode, message));
        this.ruleCode = ruleCode;
    }

    public String getRuleCode() {
        return ruleCode;
    }
}
