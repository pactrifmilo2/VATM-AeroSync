package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    Optional<SyncJob> findByFileHash(String fileHash);

    List<SyncJob> findByStatus(SyncStatus status);

    Page<SyncJob> findByStatus(SyncStatus status, Pageable pageable);

    List<SyncJob> findByStatusAndUpdatedAtBefore(SyncStatus status, LocalDateTime cutoff);

    long countByStatus(SyncStatus status);

    long countByUpdatedAtGreaterThanEqual(LocalDateTime since);

    long countByStatusAndUpdatedAtGreaterThanEqual(SyncStatus status, LocalDateTime since);
}
