package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.enums.PermitImportStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PermitImportRepository extends JpaRepository<PermitImport, Long> {

    Optional<PermitImport> findBySyncJobId(Long syncJobId);

    Optional<PermitImport> findFirstByNormalizedPermitIdAndStatusInOrderByCreatedAtAsc(
            String normalizedPermitId,
            Collection<PermitImportStatus> statuses);

    List<PermitImport> findByNormalizedPermitIdOrderByCreatedAtAsc(String normalizedPermitId);
}
