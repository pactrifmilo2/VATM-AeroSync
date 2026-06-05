package vatm.aerosync.worker.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.worker.entity.FlightData;

import java.util.List;

@Repository
public interface FlightDataRepository extends JpaRepository<FlightData, Long> {

    List<FlightData> findBySyncJobId(Long syncJobId);

    void deleteBySyncJobId(Long syncJobId);
}
