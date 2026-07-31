package vatm.aerosync.common.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.enums.PermitTrainingSourceState;

import java.util.Optional;

@Repository
public interface PermitTrainingSourceRepository
        extends JpaRepository<PermitTrainingSource, Long> {

    Optional<PermitTrainingSource> findByFileRecordId(Long fileRecordId);

    Page<PermitTrainingSource> findByState(
            PermitTrainingSourceState state,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select source
              from PermitTrainingSource source
             where source.id = :id
            """)
    Optional<PermitTrainingSource> findByIdForUpdate(@Param("id") Long id);
}
