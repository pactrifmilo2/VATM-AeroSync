package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.EmailMetadata;

import java.util.Optional;

@Repository
public interface EmailMetadataRepository extends JpaRepository<EmailMetadata, Long> {

    boolean existsByMessageId(String messageId);

    Optional<EmailMetadata> findBySyncJobId(Long syncJobId);
}
