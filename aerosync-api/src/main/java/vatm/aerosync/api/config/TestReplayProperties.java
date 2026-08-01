package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.test-replay")
public class TestReplayProperties {

    private boolean enabled;
    private boolean atfmWriteEnabled;
    private String atfmUrl = "";
    private String atfmUsername = "";
    private String atfmPassword = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAtfmWriteEnabled() {
        return atfmWriteEnabled;
    }

    public void setAtfmWriteEnabled(boolean atfmWriteEnabled) {
        this.atfmWriteEnabled = atfmWriteEnabled;
    }

    public String getAtfmUrl() {
        return atfmUrl;
    }

    public void setAtfmUrl(String atfmUrl) {
        this.atfmUrl = atfmUrl;
    }

    public String getAtfmUsername() {
        return atfmUsername;
    }

    public void setAtfmUsername(String atfmUsername) {
        this.atfmUsername = atfmUsername;
    }

    public String getAtfmPassword() {
        return atfmPassword;
    }

    public void setAtfmPassword(String atfmPassword) {
        this.atfmPassword = atfmPassword;
    }
}
