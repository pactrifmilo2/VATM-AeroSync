package vatm.aerosync.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.enums.FileSourceType;

import static org.assertj.core.api.Assertions.assertThat;

class FileIngestedEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesAndDeserializesToJson() throws Exception {
        FileIngestedEvent event = new FileIngestedEvent(
                10L,
                "/tmp/inbound/flight.csv",
                "sha256-hash",
                FileSourceType.EMAIL,
                true
        );

        String json = objectMapper.writeValueAsString(event);
        FileIngestedEvent result = objectMapper.readValue(json, FileIngestedEvent.class);

        assertThat(result.getSyncJobId()).isEqualTo(10L);
        assertThat(result.getTempFilePath()).isEqualTo("/tmp/inbound/flight.csv");
        assertThat(result.getFileHash()).isEqualTo("sha256-hash");
        assertThat(result.getSourceType()).isEqualTo(FileSourceType.EMAIL);
        assertThat(result.isPriority()).isTrue();
    }
}
