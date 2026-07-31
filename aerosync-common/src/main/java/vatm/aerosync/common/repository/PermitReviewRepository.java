package vatm.aerosync.common.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.enums.PermitReviewStatus;

import java.util.Optional;

public interface PermitReviewRepository extends JpaRepository<PermitReview, Long> {

    Optional<PermitReview> findByPermitImportId(Long permitImportId);

    Page<PermitReview> findByStatus(PermitReviewStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select review
              from PermitReview review
              join fetch review.permitImport permitImport
              join fetch permitImport.syncJob
             where review.id = :id
            """)
    Optional<PermitReview> findByIdForUpdate(@Param("id") Long id);
}
