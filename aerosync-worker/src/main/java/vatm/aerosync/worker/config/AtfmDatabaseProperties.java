package vatm.aerosync.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.atfm")
public class AtfmDatabaseProperties {

    private String url = "";
    private String username = "";
    private String password = "";
    private boolean writeEnabled;
    private boolean manualReviewEnabled = true;
    private int permitLockSeconds = 600;
    private int aircraftCacheTtlSeconds = 300;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public boolean isManualReviewEnabled() {
        return manualReviewEnabled;
    }

    public void setManualReviewEnabled(boolean manualReviewEnabled) {
        this.manualReviewEnabled = manualReviewEnabled;
    }

    public int getPermitLockSeconds() {
        return permitLockSeconds;
    }

    public void setPermitLockSeconds(int permitLockSeconds) {
        this.permitLockSeconds = permitLockSeconds;
    }

    public int getAircraftCacheTtlSeconds() {
        return aircraftCacheTtlSeconds;
    }

    public void setAircraftCacheTtlSeconds(int aircraftCacheTtlSeconds) {
        this.aircraftCacheTtlSeconds = aircraftCacheTtlSeconds;
    }
}
