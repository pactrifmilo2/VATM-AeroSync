package vatm.aerosync.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = FilePathPropertiesTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.file-paths.incoming=/tmp/incoming",
        "app.file-paths.processed=/tmp/processed",
        "app.file-paths.error=/tmp/error",
        "app.file-paths.quarantine=/tmp/quarantine"
})
class FilePathPropertiesTest {

    @Autowired
    private FilePathProperties properties;

    @Test
    void bindsFilePathProperties() {
        assertThat(properties.getIncoming()).isEqualTo("/tmp/incoming");
        assertThat(properties.getProcessed()).isEqualTo("/tmp/processed");
        assertThat(properties.getError()).isEqualTo("/tmp/error");
        assertThat(properties.getQuarantine()).isEqualTo("/tmp/quarantine");
    }

    @EnableConfigurationProperties(FilePathProperties.class)
    static class TestConfig {
    }
}
