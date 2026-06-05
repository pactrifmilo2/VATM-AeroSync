package vatm.aerosync.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.worker")
public class WorkerProperties {

    private long maxFileSizeBytes = 10 * 1024 * 1024;
    private int processedRetentionDays = 60;
    private int errorRetentionDays = 90;
    private long lockTtlSeconds = 600;

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getProcessedRetentionDays() {
        return processedRetentionDays;
    }

    public void setProcessedRetentionDays(int processedRetentionDays) {
        this.processedRetentionDays = processedRetentionDays;
    }

    public int getErrorRetentionDays() {
        return errorRetentionDays;
    }

    public void setErrorRetentionDays(int errorRetentionDays) {
        this.errorRetentionDays = errorRetentionDays;
    }

    public long getLockTtlSeconds() {
        return lockTtlSeconds;
    }

    public void setLockTtlSeconds(long lockTtlSeconds) {
        this.lockTtlSeconds = lockTtlSeconds;
    }
}
