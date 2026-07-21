package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailMetadataRepository extends JpaRepository<EmailMetadata, Long> {

    boolean existsByMessageId(String messageId);

    boolean existsByMessageIdAndIngestCompleteTrue(String messageId);

    Optional<EmailMetadata> findBySyncJobId(Long syncJobId);

    List<EmailMetadata> findByMailboxFolderAndUidValidityAndMessageUid(
            String mailboxFolder, Long uidValidity, Long messageUid);

    Optional<EmailMetadata> findByMailboxFolderAndUidValidityAndMessageUidAndAttachmentIndex(
            String mailboxFolder, Long uidValidity, Long messageUid, Integer attachmentIndex);

    boolean existsByMailboxFolderAndUidValidityAndMessageUidAndIngestCompleteTrue(
            String mailboxFolder, Long uidValidity, Long messageUid);

    List<EmailMetadata> findByAcknowledgementStatusIn(Collection<EmailAcknowledgementStatus> statuses);
}
