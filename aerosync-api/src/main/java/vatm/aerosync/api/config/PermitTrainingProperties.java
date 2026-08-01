package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.permit-training")
public class PermitTrainingProperties {

    private int minimumEvidence = 2;
    private boolean requireCorpusValidation = true;
    private int minimumCanarySuccesses = 3;
    private boolean assistedUiEnabled = true;

    public int getMinimumEvidence() {
        return minimumEvidence;
    }

    public void setMinimumEvidence(int minimumEvidence) {
        this.minimumEvidence = minimumEvidence;
    }

    public boolean isRequireCorpusValidation() {
        return requireCorpusValidation;
    }

    public void setRequireCorpusValidation(boolean requireCorpusValidation) {
        this.requireCorpusValidation = requireCorpusValidation;
    }

    public int getMinimumCanarySuccesses() {
        return minimumCanarySuccesses;
    }

    public void setMinimumCanarySuccesses(int minimumCanarySuccesses) {
        this.minimumCanarySuccesses = minimumCanarySuccesses;
    }

    public boolean isAssistedUiEnabled() {
        return assistedUiEnabled;
    }

    public void setAssistedUiEnabled(boolean assistedUiEnabled) {
        this.assistedUiEnabled = assistedUiEnabled;
    }
}
