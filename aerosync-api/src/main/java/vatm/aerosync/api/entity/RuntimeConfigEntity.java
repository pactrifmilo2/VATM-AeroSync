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

    @Column(name = "max_files_per_cycle", nullable = false)
    private int maxFilesPerCycle;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "runtime_config_whitelist", joinColumns = @JoinColumn(name = "config_id"))
    @Column(name = "sender")
    private List<String> whitelistSenders = new ArrayList<>();

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
        this.whitelistSenders = new ArrayList<>(whitelistSenders);
    }
}
