package vatm.aerosync.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.atfm")
public class AtfmDatabaseProperties {

    private String url = "";
    private String username = "";
    private String password = "";
    private boolean writeEnabled;
    private int permitLockSeconds = 600;

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

    public int getPermitLockSeconds() {
        return permitLockSeconds;
    }

    public void setPermitLockSeconds(int permitLockSeconds) {
        this.permitLockSeconds = permitLockSeconds;
    }
}
