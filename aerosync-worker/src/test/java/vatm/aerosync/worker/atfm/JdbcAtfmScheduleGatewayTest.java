package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAtfmScheduleGatewayTest {

    @Test
    void truncateUtf8_limitsOracleVarcharValuesByBytesWithoutSplittingCharacters() {
        String value = "A".repeat(3997) + "Việt Nam";

        String truncated = JdbcAtfmScheduleGateway.truncateUtf8(value, 4000);

        assertThat(truncated.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(4000);
        assertThat(truncated).isEqualTo("A".repeat(3997) + "Vi");
    }

    @Test
    void truncateUtf8_preservesNullAndValuesWithinTheLimit() {
        assertThat(JdbcAtfmScheduleGateway.truncateUtf8(null, 4000)).isNull();
        assertThat(JdbcAtfmScheduleGateway.truncateUtf8("Nội dung", 4000))
                .isEqualTo("Nội dung");
    }
}
