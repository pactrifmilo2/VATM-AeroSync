package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmAircraftTypeResolver;
import vatm.aerosync.worker.atfm.AtfmAirportCodeResolver;
import vatm.aerosync.worker.atfm.AtfmViaResolver;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApjSpecialScheduleProfileTest {
    private final WordPermitFormatDetector detector =
            new WordPermitFormatDetector(new DocxPermitProfileCatalog());
    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void carriesFlightIdentityAirportsAndTimesAcrossContinuationRows() {
        String raw = """
                Ha Noi, 02nd October 2026
                №: OF - 66/A/W/2026VN
                IATA Seasons: Winter 2026
                1.1 Name: Peach Aviation Limited
                ICAO code: APJ, IATA code: MM
                3. Schedules: All times in UTC
                4. Type of services: Passenger
                """;
        var schedule = List.of(
                List.of("Flight Number1", "Effective from", "Effective To", "Day(s) of services",
                        "Departure Airport2", "ETD", "Arrival Airport3", "ETA"),
                List.of("APJ91", "25OCT26", "10NOV26", "1234567", "RJBB", "1015", "VTBS", "1655"),
                List.of("", "12NOV26", "26JAN27", "1234567", "", "", "", ""));
        var routes = List.of(
                List.of("Sectors", "ATS Routes"),
                List.of("RJBB-VTBS", "A1"));
        var aircraft = List.of(
                List.of("Aircraft Type", "Registration marks", "MTOW (Ton)"),
                List.of("A320", "According to the AOC of Peach Aviation", "77.0"),
                List.of("A20N", "", "79.0"),
                List.of("A21N", "", "97.0"));
        var document = new WordPermitDocument(raw, "", raw,
                List.of(schedule, routes, aircraft),
                List.of("3. Schedules: All times in UTC", "5. Air traffic service routes", "6. Aircraft"));

        assertThat(detector.detect(document, "OF-66-APJ.doc").id())
                .isEqualTo("apj-english-seasonal-overflight-issued");
        var permit = parser.parse(document, "OF-66-APJ.doc");

        assertThat(permit.flights()).hasSize(2);
        assertThat(permit.flights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly("APJ91", "APJ91");
        assertThat(permit.flights()).extracting(ScheduleFlight::fromAirport)
                .containsOnly("RJBB");
        assertThat(permit.flights()).extracting(ScheduleFlight::toAirport)
                .containsOnly("VTBS");
        assertThat(permit.flights()).extracting(ScheduleFlight::etd)
                .containsOnly("1015");
        assertThat(permit.flights()).extracting(ScheduleFlight::eta)
                .containsOnly("1655");
        assertThat(permit.flights()).extracting(ScheduleFlight::via)
                .containsOnly("A1");
        assertThat(permit.flights().get(1).beginDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(permit.flights().get(1).endDate()).isEqualTo(LocalDate.of(2027, 1, 26));
    }

    @Test
    void parsesAnnotatedSpecialWordWhenConfigured() throws Exception {
        String configured = System.getProperty("permit.apj.special.file");
        Assumptions.assumeTrue(configured != null,
                "Set -Dpermit.apj.special.file to check the annotated APJ document");

        Path file = Path.of(configured);
        var document = new WordPermitDocumentReader().read(file);
        assertThat(detector.detect(document, file.getFileName().toString()).id())
                .isEqualTo("apj-english-seasonal-overflight-issued");

        var permit = parser.parse(document, file.getFileName().toString());
        assertThat(permit.operatorId()).isEqualTo("APJ");
        assertThat(permit.billingAddress()).isEqualTo("Peach Aviation Limited");
        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 0066A/W/CHK/2026");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(permit.flightType()).isEqualTo("SC");
        assertThat(permit.originalFlights()).isEmpty();
        assertThat(permit.flights()).hasSize(14);
        assertThat(permit.flights()).extracting(ScheduleFlight::flightNumber)
                .containsExactly(
                        "APJ91", "APJ91", "APJ91",
                        "APJ92", "APJ92", "APJ92", "APJ92",
                        "APJ773", "APJ773", "APJ773",
                        "APJ774", "APJ774", "APJ774", "APJ774");
        assertThat(permit.flights()).allSatisfy(flight -> {
            assertThat(flight.purposeId()).isEqualTo("PAX");
            assertThat(flight.fromAirport()).hasSize(4);
            assertThat(flight.toAirport()).hasSize(4);
            assertThat(flight.etd()).matches("\\d{4}");
            assertThat(flight.eta()).matches("\\d{4}\\+?");
            assertThat(flight.via()).isNotBlank();
            assertThat(flight.sourceAircraftType()).isEqualTo("A320/A20N/A21N");
        });
        assertThat(permit.flights().get(3).beginDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(permit.flights().get(6).beginDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(permit.flights().get(6).etd()).isEqualTo("1815");
        assertThat(permit.flights().get(6).eta()).isEqualTo("0020");
        assertThat(permit.flights().get(11).beginDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(permit.flights().get(11).etd()).isEqualTo("1815");
        assertThat(permit.flights().get(11).eta()).isEqualTo("0035");
        assertRows(permit.flights().subList(0, 3), "RJBB", "VTBS", "1015", "1655", "A1");
        assertRows(permit.flights().subList(3, 6), "VTBS", "RJBB", "1810", "2345", "A1");
        assertRows(permit.flights().subList(7, 10), "RJBB", "WSSS", "0940", "1655", "N892");
        assertThat(permit.flights().get(10)).satisfies(flight -> {
            assertThat(flight.fromAirport()).isEqualTo("WSSS");
            assertThat(flight.toAirport()).isEqualTo("RJBB");
            assertThat(flight.etd()).isEqualTo("1725");
            assertThat(flight.eta()).isEqualTo("0005");
            assertThat(flight.via()).isEqualTo("L625");
        });
        assertRows(permit.flights().subList(11, 14), "WSSS", "RJBB", "1815", "0035", "L625");
    }

    @Test
    void resolvesEveryInsertFieldAgainstLiveReferenceDataWhenConfigured() throws Exception {
        String configured = System.getProperty("permit.apj.special.file");
        Assumptions.assumeTrue(configured != null
                        && Boolean.getBoolean("permit.apj.reference.audit"),
                "Set the APJ file and permit.apj.reference.audit=true for SELECT-only reference checks");

        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl(requiredEnvironment("APP_ATFM_DATASOURCE_URL"));
        properties.setUsername(requiredEnvironment("APP_ATFM_DATASOURCE_USERNAME"));
        properties.setPassword(requiredEnvironment("APP_ATFM_DATASOURCE_PASSWORD"));

        Path file = Path.of(configured);
        var permit = parser.parse(file, file.getFileName().toString());
        var context = new ProcessingContext(new FileIngestedEvent(
                1L, file.toString(), "38101773", FileSourceType.FILESYSTEM, false));
        context.setSchedulePermit(permit);

        new PurposeResolutionStep(properties).resolve(context);
        new AircraftTypeResolutionStep(
                new AircraftTypeCatalog(), new AtfmAircraftTypeResolver(properties))
                .resolveAllowingMissingForNonSeasonal(context);
        new ViaResolutionStep(properties, new AtfmAirportCodeResolver(), new AtfmViaResolver())
                .resolveAllowingMissingForNonSeasonal(context);
        new BusinessRuleValidatorStep().validate(context);

        assertThat(context.getRowValidationErrors()).isEmpty();
        assertThat(context.getSchedulePermit().flights()).hasSize(14).allSatisfy(flight -> {
            assertThat(flight.purposeId()).isEqualTo("PAX");
            assertThat(flight.craftId()).isEqualTo(9L);
            assertThat(flight.sourceAircraftType()).isEqualTo("A320/A20N/A21N");
            assertThat(flight.via()).isNotBlank();
        });
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name);
        }
        return value;
    }

    private void assertRows(List<ScheduleFlight> flights, String from, String to,
                            String etd, String eta, String via) {
        assertThat(flights).allSatisfy(flight -> {
            assertThat(flight.fromAirport()).isEqualTo(from);
            assertThat(flight.toAirport()).isEqualTo(to);
            assertThat(flight.etd()).isEqualTo(etd);
            assertThat(flight.eta()).isEqualTo(eta);
            assertThat(flight.via()).isEqualTo(via);
        });
    }
}
