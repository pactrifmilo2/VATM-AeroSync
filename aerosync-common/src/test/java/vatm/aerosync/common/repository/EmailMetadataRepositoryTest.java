package vatm.aerosync.common.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class EmailMetadataRepositoryTest {

    @Autowired
    private EmailMetadataRepository repository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Test
    void checksExistenceByMessageId() {
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId("repo-message-001");
        metadata.setMailboxFolder("INBOX");
        metadata.setUidValidity(1L);
        metadata.setMessageUid(10L);
        metadata.setAttachmentIndex(0);
        metadata.setIngestComplete(true);
        repository.saveAndFlush(metadata);

        assertThat(repository.existsByMessageId("repo-message-001")).isTrue();
        assertThat(repository.existsByMessageId("missing-message")).isFalse();
        assertThat(repository.existsByMailboxFolderAndUidValidityAndMessageUidAndIngestCompleteTrue(
                "INBOX", 1L, 10L)).isTrue();
        assertThat(repository.findByMailboxFolderAndUidValidityAndMessageUid(
                "INBOX", 1L, 10L)).containsExactly(metadata);
    }

    @Test
    void findsFirstMetadataWhenMultipleEmailsReferenceTheSameSyncJob() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("shared-attachment-hash");
        syncJob = syncJobRepository.saveAndFlush(syncJob);

        EmailMetadata first = metadata("first-message", 11L, syncJob);
        EmailMetadata second = metadata("second-message", 12L, syncJob);
        repository.saveAndFlush(first);
        repository.saveAndFlush(second);

        assertThat(repository.findFirstBySyncJobIdOrderByIdAsc(syncJob.getId()))
                .contains(first);
    }

    private EmailMetadata metadata(String messageId, long messageUid, SyncJob syncJob) {
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId(messageId);
        metadata.setMailboxFolder("INBOX");
        metadata.setUidValidity(1L);
        metadata.setMessageUid(messageUid);
        metadata.setAttachmentIndex(0);
        metadata.setSyncJob(syncJob);
        return metadata;
    }
}
