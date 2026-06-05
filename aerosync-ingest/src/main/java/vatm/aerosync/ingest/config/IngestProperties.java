package vatm.aerosync.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ingest")
public class IngestProperties {

    private int maxFilesPerCycle = 100;
    private long schedulerFixedDelayMs = 300_000L;

    public int getMaxFilesPerCycle() {
        return maxFilesPerCycle;
    }

    public void setMaxFilesPerCycle(int maxFilesPerCycle) {
        this.maxFilesPerCycle = maxFilesPerCycle;
    }

    public long getSchedulerFixedDelayMs() {
        return schedulerFixedDelayMs;
    }

    public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
        this.schedulerFixedDelayMs = schedulerFixedDelayMs;
    }
}
