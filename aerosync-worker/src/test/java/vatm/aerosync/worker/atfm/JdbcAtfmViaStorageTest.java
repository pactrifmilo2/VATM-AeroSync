package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAtfmViaStorageTest {

    @Test
    void prefixesEveryStoredRouteExactlyOnce() {
        String expected = "Other permitted airways published in Viet Nams AIP/G474/L628";
        assertThat(JdbcAtfmScheduleGateway.viaForStorage(
                "Other permitted airways published in Viet Nam's AIP/G474/L628")).isEqualTo(expected);
        assertThat(JdbcAtfmScheduleGateway.viaForStorage(
                "Other permitted airways published in Viet Nam" + (char) 0x2019
                        + "s AIP/G474/L628")).isEqualTo(expected);
        assertThat(JdbcAtfmScheduleGateway.viaForStorage("G474/L628")).isEqualTo(expected);
        assertThat(JdbcAtfmScheduleGateway.viaForStorage(expected)).isEqualTo(expected);
        assertThat(JdbcAtfmScheduleGateway.viaForComparison(expected)).isEqualTo("G474/L628");
        assertThat(JdbcAtfmScheduleGateway.viaForStorage(null)).isNull();
    }
}
