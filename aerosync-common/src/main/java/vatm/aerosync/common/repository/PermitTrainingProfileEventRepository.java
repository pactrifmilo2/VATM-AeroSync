package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;

import java.util.List;

@Repository
public interface PermitTrainingProfileEventRepository
        extends JpaRepository<PermitTrainingProfileEvent, Long> {

    List<PermitTrainingProfileEvent>
    findByTrainingProfileIdOrderByCreatedAtAsc(Long trainingProfileId);
}
