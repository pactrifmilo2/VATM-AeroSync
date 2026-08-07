package vatm.aerosync.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("app.api")
public class ApiProperties {

    private final Defaults defaults = new Defaults();

    public Defaults getDefaults() {
        return defaults;
    }

    public static class Defaults {

        private long schedulerFixedDelayMs = 300_000L;
        private String ingestionMode = "EMAIL";
        private long folderPollingIntervalMs = 60_000L;
        private int maxFilesPerCycle = 100;
        private List<String> blacklistSenders = new ArrayList<>(List.of("ops@vatm.local"));
        private String incomingDir = "D:/vatm-storage/incoming";
        private String processedDir = "D:/vatm-storage/processed";
        private String errorDir = "D:/vatm-storage/error";
        private String emailHost = "mail.vatm.vn";
        private int emailPort = 993;
        private String emailProtocol = "IMAP SSL/TLS";
        private String emailUser = "system_slb@vatm.vn";
        private String emailPassword = "";
        private String retryMode = "Exponential";
        private int maxSizePerFileMb = 10;
        private boolean autoQuarantine = true;
        private boolean skipDuplicateIdempotency = true;
        private boolean sendZaloAlert = false;

        public long getSchedulerFixedDelayMs() {
            return schedulerFixedDelayMs;
        }

        public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
            this.schedulerFixedDelayMs = schedulerFixedDelayMs;
        }

        public String getIngestionMode() {
            return ingestionMode;
        }

        public void setIngestionMode(String ingestionMode) {
            this.ingestionMode = ingestionMode;
        }

        public long getFolderPollingIntervalMs() {
            return folderPollingIntervalMs;
        }

        public void setFolderPollingIntervalMs(long folderPollingIntervalMs) {
            this.folderPollingIntervalMs = folderPollingIntervalMs;
        }

        public int getMaxFilesPerCycle() {
            return maxFilesPerCycle;
        }

        public void setMaxFilesPerCycle(int maxFilesPerCycle) {
            this.maxFilesPerCycle = maxFilesPerCycle;
        }

        public List<String> getBlacklistSenders() {
            return blacklistSenders;
        }

        public void setBlacklistSenders(List<String> blacklistSenders) {
            this.blacklistSenders = blacklistSenders;
        }

        public String getIncomingDir() {
            return incomingDir;
        }

        public void setIncomingDir(String incomingDir) {
            this.incomingDir = incomingDir;
        }

        public String getProcessedDir() {
            return processedDir;
        }

        public void setProcessedDir(String processedDir) {
            this.processedDir = processedDir;
        }

        public String getErrorDir() {
            return errorDir;
        }

        public void setErrorDir(String errorDir) {
            this.errorDir = errorDir;
        }

        public String getEmailHost() {
            return emailHost;
        }

        public void setEmailHost(String emailHost) {
            this.emailHost = emailHost;
        }

        public int getEmailPort() {
            return emailPort;
        }

        public void setEmailPort(int emailPort) {
            this.emailPort = emailPort;
        }

        public String getEmailProtocol() {
            return emailProtocol;
        }

        public void setEmailProtocol(String emailProtocol) {
            this.emailProtocol = emailProtocol;
        }

        public String getEmailUser() {
            return emailUser;
        }

        public void setEmailUser(String emailUser) {
            this.emailUser = emailUser;
        }

        public String getEmailPassword() {
            return emailPassword;
        }

        public void setEmailPassword(String emailPassword) {
            this.emailPassword = emailPassword;
        }

        public String getRetryMode() {
            return retryMode;
        }

        public void setRetryMode(String retryMode) {
            this.retryMode = retryMode;
        }

        public int getMaxSizePerFileMb() {
            return maxSizePerFileMb;
        }

        public void setMaxSizePerFileMb(int maxSizePerFileMb) {
            this.maxSizePerFileMb = maxSizePerFileMb;
        }

        public boolean isAutoQuarantine() {
            return autoQuarantine;
        }

        public void setAutoQuarantine(boolean autoQuarantine) {
            this.autoQuarantine = autoQuarantine;
        }

        public boolean isSkipDuplicateIdempotency() {
            return skipDuplicateIdempotency;
        }

        public void setSkipDuplicateIdempotency(boolean skipDuplicateIdempotency) {
            this.skipDuplicateIdempotency = skipDuplicateIdempotency;
        }

        public boolean isSendZaloAlert() {
            return sendZaloAlert;
        }

        public void setSendZaloAlert(boolean sendZaloAlert) {
            this.sendZaloAlert = sendZaloAlert;
        }
    }
}
