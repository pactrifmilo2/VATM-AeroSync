package vatm.aerosync.worker.atfm;

import vatm.aerosync.worker.model.ScheduleFlight;

import java.util.List;

public record AtfmRevisionBaseline(long masterId,
                                   long permId,
                                   String operatorId,
                                   String scheduledPermitId,
                                   List<ScheduleFlight> flights) {
    public AtfmRevisionBaseline {
        flights = List.copyOf(flights);
    }

    public AtfmRevisionBaseline(long masterId, long permId, List<ScheduleFlight> flights) {
        this(masterId, permId, null, null, flights);
    }

    public AtfmRevisionBaseline(long masterId,
                                long permId,
                                String operatorId,
                                List<ScheduleFlight> flights) {
        this(masterId, permId, operatorId, null, flights);
    }

    public boolean scheduledStorage() {
        return scheduledPermitId != null && !scheduledPermitId.isBlank();
    }

    public boolean seasonalStorage() {
        return scheduledStorage()
                && scheduledPermitId.matches("(?i).*/(?:S|W)/.*");
    }

    public boolean multiDayNonScheduledStorage() {
        return scheduledStorage() && !seasonalStorage();
    }
}
