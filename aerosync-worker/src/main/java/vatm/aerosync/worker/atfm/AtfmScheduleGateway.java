package vatm.aerosync.worker.atfm;

import vatm.aerosync.worker.model.SchedulePermit;

import java.util.Optional;

public interface AtfmScheduleGateway {

    Optional<AtfmPermitSnapshot> findExisting(SchedulePermit permit);

    AtfmWriteResult insert(SchedulePermit permit);
}
