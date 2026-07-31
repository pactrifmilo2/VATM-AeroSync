package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermitTrainingProfileEvidenceRepository
        extends JpaRepository<PermitTrainingProfileEvidence, Long> {

    List<PermitTrainingProfileEvidence>
    findByTrainingProfileIdOrderByCreatedAtAsc(Long trainingProfileId);

    Optional<PermitTrainingProfileEvidence>
    findByTrainingProfileIdAndTrainingSourceId(
            Long trainingProfileId,
            Long trainingSourceId);

    Optional<PermitTrainingProfileEvidence>
    findByIdAndTrainingProfileId(Long id, Long trainingProfileId);
}
