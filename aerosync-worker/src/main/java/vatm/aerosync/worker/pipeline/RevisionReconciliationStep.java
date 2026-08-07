package vatm.aerosync.worker.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

/**
 * Keeps the replacement schedule selected by the parser intact.
 *
 * A revision document may contain both an old/original schedule and a new
 * schedule.  The parser stores the latter in {@code flights} and the former in
 * {@code originalFlights}; only {@code flights} is sent to the writer.  Older
 * code tried to match every original row against ATFM before writing, which
 * incorrectly quarantined valid revisions when the base permit was absent or
 * formatted differently.  Revisions are now written directly: an existing
 * permit receives only the new rows, and a missing target is inserted as a new
 * permit by the coordinator.
 */
@Component
public class RevisionReconciliationStep {

    /** Kept in the constructor for compatibility with existing wiring/tests. */
    @Autowired
    public RevisionReconciliationStep(AtfmScheduleGateway ignoredGateway,
                                      AirportCodeCatalog ignoredAirportCodeCatalog) {
    }

    public RevisionReconciliationStep(AtfmScheduleGateway ignoredGateway) {
        this(ignoredGateway, new AirportCodeCatalog());
    }

    public void reconcile(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        if (permit != null && permit.revision()) {
            // Explicitly discard the old/original table from the downstream
            // write path.  No ATFM baseline lookup or row pairing is performed.
            context.setSchedulePermit(permit.withFlights(permit.flights()));
        }
    }
}
