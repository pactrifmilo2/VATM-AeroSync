package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;

import java.util.List;

@Repository
public interface PermitTrainingProfileEvidenceRepository
        extends JpaRepository<PermitTrainingProfileEvidence, Long> {

    List<PermitTrainingProfileEvidence>
    findByTrainingProfileIdOrderByCreatedAtAsc(Long trainingProfileId);
}
