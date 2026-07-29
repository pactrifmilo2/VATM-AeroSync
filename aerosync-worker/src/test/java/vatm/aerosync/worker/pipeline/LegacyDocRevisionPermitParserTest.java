package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyDocRevisionPermitParserTest {

    private final LegacyDocRevisionPermitParser parser = new LegacyDocRevisionPermitParser();

    @Test
    void parseContent_mapsOnlyNewLandingRevisionSchedule() {
        SchedulePermit permit = parser.parseContent(rawContent(), tables("767F"), "revision.doc");

        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-06/A/S/2026");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD-06/A/S/2026");
        assertThat(permit.permitNumber()).isEqualTo("06");
        assertThat(permit.permitType()).isEqualTo("LD");
        assertThat(permit.authorId()).isEqualTo("CHK");
        assertThat(permit.version()).isEqualTo("A");
        assertThat(permit.season()).isEqualTo("S");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(permit.operatorId()).isEqualTo("FDX");
        assertThat(permit.reference()).isEqualTo("LD-06/A/S/2026VN/REV7");
        assertThat(permit.validHours()).isEqualTo(24);
        assertThat(permit.billingAddress()).contains("3131 Democrat Rd");

        assertThat(permit.flights()).hasSize(2)
                .extracting(flight -> flight.flightNumber())
                .containsOnly("FX606D");
        assertThat(permit.flights().getFirst()).satisfies(flight -> {
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 16));
            assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 7, 16));
            assertThat(flight.serviceDays()).isEqualTo("0004000");
            assertThat(flight.fromAirport()).isEqualTo("SIN");
            assertThat(flight.toAirport()).isEqualTo("SGN");
            assertThat(flight.etd()).isEqualTo("2215");
            assertThat(flight.eta()).isEqualTo("0030");
            assertThat(flight.via()).isEqualTo("M753");
            assertThat(flight.craftId()).isZero();
            assertThat(flight.mtow()).isNull();
            assertThat(flight.sourceAircraftType()).isEqualTo("767F");
            assertThat(flight.remark()).isEqualTo("CAR 767F");
        });
        assertThat(permit.flights().get(1)).satisfies(flight -> {
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 7, 17));
            assertThat(flight.serviceDays()).isEqualTo("0000500");
            assertThat(flight.fromAirport()).isEqualTo("SGN");
            assertThat(flight.toAirport()).isEqualTo("CAN");
            assertThat(flight.via()).isEqualTo("N500 M771");
        });
    }

    @Test
    void parseContent_preservesUnknownAircraftForDatabaseResolution() {
        SchedulePermit permit = parser.parseContent(rawContent(), tables("UNKNOWN"), "revision.doc");

        assertThat(permit.flights())
                .extracting(flight -> flight.sourceAircraftType())
                .containsOnly("UNKNOWN");
    }

    @Test
    void parse_mapsConfiguredRealLegacyPermitDocument() {
        String samplePath = System.getProperty("permit.legacy.sample.path");
        Assumptions.assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set -Dpermit.legacy.sample.path to validate a real legacy permit document");
        Path file = Path.of(samplePath);
        Assumptions.assumeTrue(Files.isRegularFile(file), "Configured legacy permit document does not exist");

        SchedulePermit permit = parser.parse(file, file.getFileName().toString());

        assertThat(permit.normalizedPermitId()).isEqualTo("LD-06/A/S/2026");
        assertThat(permit.operatorId()).isEqualTo("FDX");
        assertThat(permit.flights()).hasSize(2)
                .allMatch(flight -> flight.flightNumber().equals("FX606D"));
    }

    private String rawContent() {
        return """
                Revision of landing/overflight permit
                Hanoi, 16/07/2026 Permit No.: LD-06/A/S/2026VN/REV8
                ICAO code: FDX
                Postal Address: 3131 Democrat Rd, Bldg. C, Memphis, TN 38118 USA.
                2. Schedule (UTC Time)
                2.1. Original schedule(s)
                2.2. New schedule(s)
                4. VALIDITY: -12/+24 HOURS WINDOW FOR PSBL DELAY
                """;
    }

    private List<List<List<String>>> tables(String aircraftType) {
        List<List<String>> operator = List.of(
                List.of("Name: FEDERAL EXPRESS CORPORATION"),
                List.of("IATA code: FX", "ICAO code: FDX"));
        List<List<String>> original = List.of(
                List.of("Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA", "Aircraft Type", "Original permit"),
                List.of("FX6067", "16Jul26", "16Jul26", "---4---",
                        "SIN", "1100", "SGN", "1315", "76F", "LD-06/A/S/2026VN/REV7"));
        List<List<String>> replacement = List.of(
                List.of("Flight number", "Effective from", "Effective to", "Days of services",
                        "Departure Airport", "ETD", "Arrival Airport", "ETA", "Aircraft Type"),
                List.of("FX606D", "16Jul26", "16Jul26", "---4---",
                        "SIN", "2215", "SGN", "0030", aircraftType),
                List.of("FX606D", "17Jul26", "17Jul26", "----5--",
                        "SGN", "0200", "CAN", "0455", aircraftType));
        List<List<String>> routes = List.of(
                List.of("Sector", "Airways", "Entry Point into Vietnam FIR", "Exit Point from Vietnam FIR"),
                List.of("SIN-SGN", "M753", "IPRIX", ""),
                List.of("SGN-CAN", "N500 M771", "", "DONDA"));
        return List.of(operator, original, replacement, routes);
    }
}
