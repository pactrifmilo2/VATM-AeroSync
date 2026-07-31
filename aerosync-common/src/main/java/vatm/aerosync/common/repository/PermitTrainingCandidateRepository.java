package vatm.aerosync.common.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;

import java.util.List;
import java.util.Optional;

public interface PermitTrainingCandidateRepository
        extends JpaRepository<PermitTrainingCandidate, Long> {

    Page<PermitTrainingCandidate> findByStatus(
            PermitTrainingStatus status,
            Pageable pageable);

    Page<PermitTrainingCandidate> findByProfileId(
            String profileId,
            Pageable pageable);

    Page<PermitTrainingCandidate> findByStatusAndProfileId(
            PermitTrainingStatus status,
            String profileId,
            Pageable pageable);

    List<PermitTrainingCandidate> findAllByStatus(PermitTrainingStatus status);

    List<PermitTrainingCandidate>
    findByProfileIdAndProfileVersionAndCanonicalAliasAndStatus(
            String profileId,
            int profileVersion,
            String canonicalAlias,
            PermitTrainingStatus status);

    boolean existsBySourceReviewIdAndSemanticFieldAndCanonicalAlias(
            Long sourceReviewId,
            String semanticField,
            String canonicalAlias);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate
              from PermitTrainingCandidate candidate
              join fetch candidate.sourceReview review
             where candidate.id = :id
            """)
    Optional<PermitTrainingCandidate> findByIdForUpdate(@Param("id") Long id);
}
