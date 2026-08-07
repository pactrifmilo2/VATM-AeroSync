package vatm.aerosync.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.api.entity.DashboardAlert;

import java.util.List;

@Repository
public interface DashboardAlertRepository extends JpaRepository<DashboardAlert, Long> {

    List<DashboardAlert> findByResolvedFalseOrderByCreatedAtDesc();

    List<DashboardAlert> findBySyncJobId(Long syncJobId);

    long countByResolvedFalse();
}
