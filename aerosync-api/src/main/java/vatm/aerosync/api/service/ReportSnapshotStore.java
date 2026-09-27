package vatm.aerosync.api.service;

import org.springframework.stereotype.Component;
import vatm.aerosync.api.dto.EmailReportRowResponse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReportSnapshotStore {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public Snapshot create(String source, List<EmailReportRowResponse> rows) {
        OffsetDateTime now = OffsetDateTime.now();
        snapshots.entrySet().removeIf(entry -> entry.getValue().createdAt().plus(TTL).isBefore(now));
        Snapshot snapshot = new Snapshot(UUID.randomUUID().toString(), source, now, List.copyOf(rows));
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }

    public Snapshot require(String id, String source) {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null || !snapshot.source().equals(source)
                || snapshot.createdAt().plus(TTL).isBefore(OffsetDateTime.now())) {
            if (snapshot != null) snapshots.remove(id);
            throw new IllegalArgumentException("Snapshot is missing, expired, or belongs to another report source");
        }
        return snapshot;
    }

    public record Snapshot(String id, String source, OffsetDateTime createdAt,
                           List<EmailReportRowResponse> rows) { }
}
