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
        private int maxFilesPerCycle = 100;
        private List<String> whitelistSenders = new ArrayList<>(List.of("ops@vatm.local"));

        public long getSchedulerFixedDelayMs() {
            return schedulerFixedDelayMs;
        }

        public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
            this.schedulerFixedDelayMs = schedulerFixedDelayMs;
        }

        public int getMaxFilesPerCycle() {
            return maxFilesPerCycle;
        }

        public void setMaxFilesPerCycle(int maxFilesPerCycle) {
            this.maxFilesPerCycle = maxFilesPerCycle;
        }

        public List<String> getWhitelistSenders() {
            return whitelistSenders;
        }

        public void setWhitelistSenders(List<String> whitelistSenders) {
            this.whitelistSenders = whitelistSenders;
        }
    }
}
