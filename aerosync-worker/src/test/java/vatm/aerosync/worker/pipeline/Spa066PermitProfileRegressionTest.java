package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class Spa066PermitProfileRegressionTest {

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parse_mapsOnlyNewVietnameseLandingScheduleFromSampleDocument() throws Exception {
        Path sample = Path.of(getClass().getClassLoader()
                .getResource("samples/spa066-landing-revision.docx")
                .toURI());

        SchedulePermit permit = parser.parse(sample, sample.getFileName().toString());

        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-2631/07/2026VN-REV1");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD-2631/07/2026");
        assertThat(permit.permitNumber()).isEqualTo("2631");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(permit.operatorId()).isEqualTo("SPQ");
        assertThat(permit.reference()).isEqualTo("LD-2631/07/2026VN");
        assertThat(permit.permitType()).isEqualTo("LD");
        assertThat(permit.validHours()).isEqualTo(24);
        assertThat(permit.flightType()).isEqualTo("SC");
        assertThat(permit.iataAirportsAllowed()).isTrue();
        assertThat(permit.emptyAirwaysAllowed()).isTrue();
        assertThat(permit.billingAddress()).contains("Sun Grand City");

        assertThat(permit.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("9G855");
            assertThat(flight.purposeId()).isEqualTo("PAX");
            assertThat(flight.craftId()).isEqualTo(6021L);
            assertThat(flight.mtow()).isEqualByComparingTo(new BigDecimal("89"));
            assertThat(flight.serviceDays()).isEqualTo("0000060");
            assertThat(flight.fromAirport()).isEqualTo("HAN");
            assertThat(flight.toAirport()).isEqualTo("SGN");
            assertThat(flight.etd()).isEqualTo("0855");
            assertThat(flight.eta()).isEqualTo("1105");
            assertThat(flight.via()).isNull();
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 18));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 18));
            assertThat(flight.remark()).isEqualTo("PAX 321/32Q/32N");
        });

        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, sample.toString(), "spa066", FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit);
        assertDoesNotThrow(() -> new BusinessRuleValidatorStep().validate(context));
    }
}
