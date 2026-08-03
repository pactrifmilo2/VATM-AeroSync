package vatm.aerosync.worker.atfm;

import vatm.aerosync.worker.model.ScheduleFlight;

import java.util.List;

public record AtfmRevisionBaseline(long masterId, long permId, List<ScheduleFlight> flights) {
    public AtfmRevisionBaseline {
        flights = List.copyOf(flights);
    }
}
