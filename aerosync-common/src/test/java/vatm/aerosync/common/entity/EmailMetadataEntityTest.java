package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class EmailMetadataEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsEmailMetadataFields() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 6, 3, 9, 0);
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId("message-001");
        metadata.setSender("ops@example.com");
        metadata.setSubject("Flight update");
        metadata.setReceivedAt(receivedAt);
        metadata.setAttachmentCount(2);
        metadata.setMailboxFolder("INBOX");
        metadata.setUidValidity(101L);
        metadata.setMessageUid(5001L);
        metadata.setAttachmentIndex(0);
        metadata.setAttachmentName("flight.csv");
        metadata.setProcessingStatus(EmailProcessingStatus.DOWNLOADED);

        EmailMetadata persisted = entityManager.persistFlushFind(metadata);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getMessageId()).isEqualTo("message-001");
        assertThat(persisted.getSender()).isEqualTo("ops@example.com");
        assertThat(persisted.getSubject()).isEqualTo("Flight update");
        assertThat(persisted.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(persisted.getAttachmentCount()).isEqualTo(2);
        assertThat(persisted.getMailboxFolder()).isEqualTo("INBOX");
        assertThat(persisted.getMessageUid()).isEqualTo(5001L);
        assertThat(persisted.getAttachmentName()).isEqualTo("flight.csv");
        assertThat(persisted.getProcessingStatus()).isEqualTo(EmailProcessingStatus.DOWNLOADED);
        assertThat(persisted.getAcknowledgementStatus()).isEqualTo(EmailAcknowledgementStatus.PENDING);
    }

    @Test
    void mailboxUidAndAttachmentIndexMustBeUnique() {
        EmailMetadata first = new EmailMetadata();
        setMailboxIdentity(first, "message-first");
        entityManager.persistAndFlush(first);

        EmailMetadata duplicate = new EmailMetadata();
        setMailboxIdentity(duplicate, "message-second");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(RuntimeException.class);
    }

    private void setMailboxIdentity(EmailMetadata metadata, String messageId) {
        metadata.setMessageId(messageId);
        metadata.setMailboxFolder("INBOX");
        metadata.setUidValidity(101L);
        metadata.setMessageUid(5001L);
        metadata.setAttachmentIndex(0);
    }
}
