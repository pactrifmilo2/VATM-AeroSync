package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vatm.aerosync.common.entity.PermitTrainingDecision;

import java.util.List;

public interface PermitTrainingDecisionRepository
        extends JpaRepository<PermitTrainingDecision, Long> {

    List<PermitTrainingDecision>
    findByCandidateIdOrderByCreatedAtAscIdAsc(Long candidateId);
}
