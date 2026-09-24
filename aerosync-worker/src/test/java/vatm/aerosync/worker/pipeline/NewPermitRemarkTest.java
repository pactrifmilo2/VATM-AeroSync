package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NewPermitRemarkTest {
    @Test
    void storesOnlyMultipleAircraftTypesWithoutPurpose() {
        assertThat(DatabaseWriterStep.newPermitRemark(null)).isNull();
        assertThat(DatabaseWriterStep.newPermitRemark("A321")).isNull();
        assertThat(DatabaseWriterStep.newPermitRemark("A321/A321")).isNull();
        assertThat(DatabaseWriterStep.newPermitRemark("321/320")).isEqualTo("A321/A320");
        assertThat(DatabaseWriterStep.newPermitRemark("B787/B777/A330"))
                .isEqualTo("B787/B777/A330");
    }
}
