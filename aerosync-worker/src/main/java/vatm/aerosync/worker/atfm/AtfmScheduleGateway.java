package vatm.aerosync.worker.atfm;

import vatm.aerosync.worker.model.SchedulePermit;

import java.util.Optional;
import java.util.Set;

public interface AtfmScheduleGateway {

    Optional<AtfmPermitSnapshot> findExisting(SchedulePermit permit);

    Optional<AtfmPermitSnapshot> findNewPermitInEitherStore(SchedulePermit permit);

    Optional<AtfmRevisionBaseline> findRevisionBaseline(SchedulePermit permit);

    /** Season inherited by a new SC permit from the referenced original permit. */
    default Optional<String> findReferencedSeason(SchedulePermit permit) {
        return Optional.empty();
    }

    /** Revision numbers already used by the same seasonal base permit in ATFM. */
    default Set<Integer> findUsedRevisionNumbers(SchedulePermit permit) {
        return Set.of();
    }

    /**
     * Looks up a revision baseline in the storage table that {@code permit}
     * does NOT currently route to. Used only to produce an accurate error
     * message when a multi-day non-scheduled revision cannot find its base
     * permit under {@link #findRevisionBaseline} because the base permit is
     * still stored in the non-scheduled (NO) table while the revision itself
     * routes to the scheduled (SC) table. Never used to read/write flight data.
     */
    default Optional<AtfmRevisionBaseline> findCrossStoreRevisionBaseline(SchedulePermit permit) {
        return Optional.empty();
    }

    AtfmWriteResult insert(SchedulePermit permit);

    AtfmWriteResult update(SchedulePermit permit);
}
