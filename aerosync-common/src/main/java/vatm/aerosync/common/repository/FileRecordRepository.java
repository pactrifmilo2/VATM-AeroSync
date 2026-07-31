package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.FileRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

    List<FileRecord> findBySyncJobId(Long syncJobId);

    List<FileRecord> findBySyncJobIdIn(Collection<Long> syncJobIds);

    Optional<FileRecord> findFirstBySyncJobIdOrderByIdAsc(Long syncJobId);
}
