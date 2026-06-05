package vatm.aerosync.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.file-paths")
public class FilePathProperties {

    private String incoming;
    private String processed;
    private String error;
    private String quarantine;

    public String getIncoming() {
        return incoming;
    }

    public void setIncoming(String incoming) {
        this.incoming = incoming;
    }

    public String getProcessed() {
        return processed;
    }

    public void setProcessed(String processed) {
        this.processed = processed;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getQuarantine() {
        return quarantine;
    }

    public void setQuarantine(String quarantine) {
        this.quarantine = quarantine;
    }
}
