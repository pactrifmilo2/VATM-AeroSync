package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    Optional<SyncJob> findByFileHash(String fileHash);

    List<SyncJob> findByStatus(SyncStatus status);

    long countByStatus(SyncStatus status);
}
