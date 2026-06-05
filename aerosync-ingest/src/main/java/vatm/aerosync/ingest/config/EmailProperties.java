package vatm.aerosync.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("app.email")
public class EmailProperties {

    private String host = "localhost";
    private int port = 993;
    private String username;
    private String password;
    private String protocol = "imaps";
    private String folder = "INBOX";
    private List<String> whitelistSenders = new ArrayList<>();
    private int connectionTimeoutMs = 10_000;
    private Path stagingDir = Path.of(System.getProperty("java.io.tmpdir"), "aerosync-email-staging");

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
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

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public List<String> getWhitelistSenders() {
        return whitelistSenders;
    }

    public void setWhitelistSenders(List<String> whitelistSenders) {
        this.whitelistSenders = whitelistSenders;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public Path getStagingDir() {
        return stagingDir;
    }

    public void setStagingDir(Path stagingDir) {
        this.stagingDir = stagingDir;
    }
}
