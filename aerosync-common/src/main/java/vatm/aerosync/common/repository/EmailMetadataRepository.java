package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailMetadataRepository
        extends JpaRepository<EmailMetadata, Long>, JpaSpecificationExecutor<EmailMetadata> {

    boolean existsByMessageId(String messageId);

    boolean existsByMessageIdAndIngestCompleteTrue(String messageId);

    Optional<EmailMetadata> findFirstBySyncJobIdOrderByIdAsc(Long syncJobId);

    List<EmailMetadata> findBySyncJobIdIn(Collection<Long> syncJobIds);

    List<EmailMetadata> findByMailboxFolderAndUidValidityAndMessageUid(
            String mailboxFolder, Long uidValidity, Long messageUid);

    Optional<EmailMetadata> findByMailboxFolderAndUidValidityAndMessageUidAndAttachmentIndex(
            String mailboxFolder, Long uidValidity, Long messageUid, Integer attachmentIndex);

    boolean existsByMailboxFolderAndUidValidityAndMessageUidAndIngestCompleteTrue(
            String mailboxFolder, Long uidValidity, Long messageUid);

    List<EmailMetadata> findByAcknowledgementStatusIn(Collection<EmailAcknowledgementStatus> statuses);

    @Query("""
            select metadata.processingStatus as status, count(metadata) as total
            from EmailMetadata metadata
            where (:from is null or metadata.receivedAt >= :from)
              and (:to is null or metadata.receivedAt <= :to)
            group by metadata.processingStatus
            """)
    List<ProcessingStatusCount> countByProcessingStatus(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
            select metadata.acknowledgementStatus as status, count(metadata) as total
            from EmailMetadata metadata
            where (:from is null or metadata.receivedAt >= :from)
              and (:to is null or metadata.receivedAt <= :to)
            group by metadata.acknowledgementStatus
            """)
    List<AcknowledgementStatusCount> countByAcknowledgementStatus(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    interface ProcessingStatusCount {
        EmailProcessingStatus getStatus();

        long getTotal();
    }

    interface AcknowledgementStatusCount {
        EmailAcknowledgementStatus getStatus();

        long getTotal();
    }
}
