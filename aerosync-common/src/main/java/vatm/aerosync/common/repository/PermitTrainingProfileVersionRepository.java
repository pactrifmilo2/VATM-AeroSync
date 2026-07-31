package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;

import java.util.List;

@Repository
public interface PermitTrainingProfileVersionRepository
        extends JpaRepository<PermitTrainingProfileVersion, Long> {

    List<PermitTrainingProfileVersion>
    findByProfileKeyOrderByProfileVersionDesc(String profileKey);
}
