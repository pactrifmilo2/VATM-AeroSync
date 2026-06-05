package vatm.aerosync.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SyncResultEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesAndDeserializesToJson() throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2026, 6, 3, 10, 30);
        SyncResultEvent event = new SyncResultEvent(
                20L,
                SyncStatus.SUCCESS,
                AlertLevel.INFO,
                "Sync completed",
                timestamp
        );

        String json = objectMapper.writeValueAsString(event);
        SyncResultEvent result = objectMapper.readValue(json, SyncResultEvent.class);

        assertThat(result.getSyncJobId()).isEqualTo(20L);
        assertThat(result.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(result.getAlertLevel()).isEqualTo(AlertLevel.INFO);
        assertThat(result.getMessage()).isEqualTo("Sync completed");
        assertThat(result.getTimestamp()).isEqualTo(timestamp);
    }
}
