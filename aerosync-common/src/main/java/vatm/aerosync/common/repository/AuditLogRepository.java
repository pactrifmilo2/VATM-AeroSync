package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to);

    List<AuditLog> findByResultStatus(SyncStatus status);
}
