package vatm.aerosync.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.api.entity.RuntimeConfigEntity;

@Repository
public interface RuntimeConfigRepository extends JpaRepository<RuntimeConfigEntity, Long> {
}
