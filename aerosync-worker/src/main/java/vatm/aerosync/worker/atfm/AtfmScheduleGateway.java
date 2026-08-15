package vatm.aerosync.worker.atfm;

import vatm.aerosync.worker.model.SchedulePermit;

import java.util.Optional;
import java.util.Set;

public interface AtfmScheduleGateway {

    Optional<AtfmPermitSnapshot> findExisting(SchedulePermit permit);

    Optional<AtfmRevisionBaseline> findRevisionBaseline(SchedulePermit permit);

    /** Revision numbers already used by the same seasonal base permit in ATFM. */
    default Set<Integer> findUsedRevisionNumbers(SchedulePermit permit) {
        return Set.of();
    }

    AtfmWriteResult insert(SchedulePermit permit);

    AtfmWriteResult update(SchedulePermit permit);
}
