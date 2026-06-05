package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.entity.FlightData;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.repository.FlightDataRepository;

@Component
public class DatabaseWriterStep {

    private final SyncJobRepository syncJobRepository;
    private final FlightDataRepository flightDataRepository;

    public DatabaseWriterStep(SyncJobRepository syncJobRepository,
                              FlightDataRepository flightDataRepository) {
        this.syncJobRepository = syncJobRepository;
        this.flightDataRepository = flightDataRepository;
    }

    @Transactional
    public void write(ProcessingContext context) {
        Long syncJobId = context.getEvent().getSyncJobId();
        SyncJob job = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new IllegalStateException("Sync job not found: " + syncJobId));

        flightDataRepository.deleteBySyncJobId(syncJobId);

        for (FlightRow row : context.getRows()) {
            FlightData entity = new FlightData();
            entity.setSyncJobId(syncJobId);
            entity.setCallsign(row.getCallsign());
            entity.setFromAirport(row.getFrom());
            entity.setToAirport(row.getTo());
            entity.setDateFlight(row.getDateFlight());
            flightDataRepository.save(entity);
        }

        job.setStatus(SyncStatus.SUCCESS);
        syncJobRepository.save(job);
    }
}
