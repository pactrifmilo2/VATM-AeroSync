package vatm.aerosync.worker.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vatm.aerosync.worker.entity.FlightData;

import java.util.List;

// @Repository intentionally disabled: the legacy training table flight_data
// was dropped from the active worker schema. It is only enabled by the legacy
// WorkerJpaTestConfiguration when running historical tests.
public interface FlightDataRepository extends JpaRepository<FlightData, Long> {

    List<FlightData> findBySyncJobId(Long syncJobId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FlightData flight where flight.syncJobId = :syncJobId")
    int deleteBySyncJobId(@Param("syncJobId") Long syncJobId);
}
