package vatm.aerosync.api.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "runtime_config")
public class RuntimeConfigEntity {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "scheduler_fixed_delay_ms", nullable = false)
    private long schedulerFixedDelayMs;

    @Column(name = "ingestion_mode")
    private String ingestionMode = "EMAIL";

    @Column(name = "folder_polling_interval_ms")
    private Long folderPollingIntervalMs = 60_000L;

    @Column(name = "max_files_per_cycle", nullable = false)
    private int maxFilesPerCycle;

    @Column(name = "incoming_dir", nullable = false)
    private String incomingDir;

    @Column(name = "processed_dir", nullable = false)
    private String processedDir;

    @Column(name = "error_dir", nullable = false)
    private String errorDir;

    @Column(name = "email_host", nullable = false)
    private String emailHost;

    @Column(name = "email_port", nullable = false)
    private int emailPort;

    @Column(name = "email_protocol", nullable = false)
    private String emailProtocol;

    @Column(name = "email_user", nullable = false)
    private String emailUser;

    @Column(name = "email_password")
    private String emailPassword;

    @Column(name = "retry_mode", nullable = false)
    private String retryMode;

    @Column(name = "max_size_per_file_mb", nullable = false)
    private int maxSizePerFileMb;

    @Column(name = "auto_quarantine", nullable = false)
    private boolean autoQuarantine;

    @Column(name = "skip_duplicate_idempotency", nullable = false)
    private boolean skipDuplicateIdempotency;

    @Column(name = "send_zalo_alert", nullable = false)
    private boolean sendZaloAlert;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "runtime_config_blacklist", joinColumns = @JoinColumn(name = "config_id"))
    @Column(name = "sender")
    private List<String> blacklistSenders = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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
        return folderPollingIntervalMs == null ? 60_000L : folderPollingIntervalMs;
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
        this.blacklistSenders = new ArrayList<>(blacklistSenders);
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
