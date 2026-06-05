package vatm.aerosync.common.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class EmailMetadataRepositoryTest {

    @Autowired
    private EmailMetadataRepository repository;

    @Test
    void checksExistenceByMessageId() {
        EmailMetadata metadata = new EmailMetadata();
        metadata.setMessageId("repo-message-001");
        repository.saveAndFlush(metadata);

        assertThat(repository.existsByMessageId("repo-message-001")).isTrue();
        assertThat(repository.existsByMessageId("missing-message")).isFalse();
    }
}
