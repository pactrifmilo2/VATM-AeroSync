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
    private String processedFolder = "AeroSync/Processed";
    private String errorFolder = "AeroSync/Error";
    private int oldestMessagesPerCycle = 5;
    private int mailboxScanWindowSize = 500;
    private boolean acknowledgementEnabled;
    private List<String> blacklistSenders = new ArrayList<>();
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

    public String getProcessedFolder() {
        return processedFolder;
    }

    public void setProcessedFolder(String processedFolder) {
        this.processedFolder = processedFolder;
    }

    public String getErrorFolder() {
        return errorFolder;
    }

    public void setErrorFolder(String errorFolder) {
        this.errorFolder = errorFolder;
    }

    public int getOldestMessagesPerCycle() {
        return oldestMessagesPerCycle;
    }

    public void setOldestMessagesPerCycle(int oldestMessagesPerCycle) {
        this.oldestMessagesPerCycle = oldestMessagesPerCycle;
    }

    public int getMailboxScanWindowSize() {
        return mailboxScanWindowSize;
    }

    public void setMailboxScanWindowSize(int mailboxScanWindowSize) {
        this.mailboxScanWindowSize = mailboxScanWindowSize;
    }

    public boolean isAcknowledgementEnabled() {
        return acknowledgementEnabled;
    }

    public void setAcknowledgementEnabled(boolean acknowledgementEnabled) {
        this.acknowledgementEnabled = acknowledgementEnabled;
    }

    public List<String> getBlacklistSenders() {
        return blacklistSenders;
    }

    public void setBlacklistSenders(List<String> blacklistSenders) {
        this.blacklistSenders = blacklistSenders;
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
