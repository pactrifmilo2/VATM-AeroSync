package vatm.aerosync.worker.atfm;

import vatm.aerosync.worker.model.ScheduleFlight;

import java.util.List;

public record AtfmRevisionBaseline(long masterId,
                                   long permId,
                                   String operatorId,
                                   List<ScheduleFlight> flights) {
    public AtfmRevisionBaseline {
        flights = List.copyOf(flights);
    }

    public AtfmRevisionBaseline(long masterId, long permId, List<ScheduleFlight> flights) {
        this(masterId, permId, null, flights);
    }
}
