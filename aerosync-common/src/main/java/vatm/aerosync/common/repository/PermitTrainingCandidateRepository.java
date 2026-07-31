package vatm.aerosync.common.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

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

    List<PermitTrainingCandidate> findAllByProfileId(String profileId);

    List<PermitTrainingCandidate> findByStatusAndProfileIdAndProfileVersion(
            PermitTrainingStatus status,
            String profileId,
            int profileVersion);

    List<PermitTrainingCandidate>
    findByProfileIdAndProfileVersionAndSemanticFieldAndCanonicalAlias(
            String profileId,
            int profileVersion,
            String semanticField,
            String canonicalAlias);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate
              from PermitTrainingCandidate candidate
              join fetch candidate.sourceReview review
             where candidate.profileId = :profileId
               and candidate.profileVersion = :profileVersion
               and candidate.canonicalAlias = :canonicalAlias
             order by candidate.id
            """)
    List<PermitTrainingCandidate> findAliasGroupForUpdate(
            @Param("profileId") String profileId,
            @Param("profileVersion") int profileVersion,
            @Param("canonicalAlias") String canonicalAlias);

    @Query("""
            select candidate
              from PermitTrainingCandidate candidate
              join fetch candidate.sourceReview review
              join fetch review.permitImport permitImport
              join fetch permitImport.syncJob syncJob
             where candidate.profileId = :profileId
               and candidate.profileVersion = :profileVersion
               and candidate.semanticField = :semanticField
               and candidate.canonicalAlias = :canonicalAlias
             order by candidate.id
            """)
    List<PermitTrainingCandidate> findValidationGroup(
            @Param("profileId") String profileId,
            @Param("profileVersion") int profileVersion,
            @Param("semanticField") String semanticField,
            @Param("canonicalAlias") String canonicalAlias);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PermitTrainingCandidate candidate
               set candidate.usageCount = candidate.usageCount + 1,
                   candidate.lastUsedAt = :usedAt
             where candidate.id = :id
               and candidate.status = :status
            """)
    int incrementUsage(
            @Param("id") Long id,
            @Param("status") PermitTrainingStatus status,
            @Param("usedAt") LocalDateTime usedAt);
}
