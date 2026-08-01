package vatm.aerosync.common.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

@Repository
public interface PermitTrainingProfileVersionRepository
        extends JpaRepository<PermitTrainingProfileVersion, Long> {

    List<PermitTrainingProfileVersion>
    findByProfileKeyOrderByProfileVersionDesc(String profileKey);

    Page<PermitTrainingProfileVersion> findByStatus(
            PermitTrainingProfileStatus status,
            Pageable pageable);

    List<PermitTrainingProfileVersion> findAllByStatus(
            PermitTrainingProfileStatus status);

    Optional<PermitTrainingProfileVersion>
    findFirstByLayoutFingerprintAndStatusInOrderByUpdatedAtDesc(
            String layoutFingerprint,
            Collection<PermitTrainingProfileStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile
              from PermitTrainingProfileVersion profile
             where profile.id = :id
            """)
    Optional<PermitTrainingProfileVersion> findByIdForUpdate(
            @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile
              from PermitTrainingProfileVersion profile
             where profile.profileKey = :profileKey
             order by profile.profileVersion desc
            """)
    List<PermitTrainingProfileVersion> findByProfileKeyForUpdate(
            @Param("profileKey") String profileKey);
}
