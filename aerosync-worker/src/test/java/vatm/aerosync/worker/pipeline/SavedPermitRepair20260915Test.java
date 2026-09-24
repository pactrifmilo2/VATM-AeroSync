package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.atfm.AtfmDocumentRouteException;
import vatm.aerosync.worker.atfm.AtfmViaResolver;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression cover for the 2026-09-15 saved-permit audit repairs.
 *
 * <p>Parse checks run against the real Word files when
 * {@code -Dpermit.repair.sample.directory} points at the storage tree
 * (searched recursively); otherwise they are skipped. Reference-resolution
 * behavior (Vietnamese "hoặc" alternative routes) is exercised directly
 * against the resolver without a database.</p>
 */
class SavedPermitRepair20260915Test {

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    // ---------- VASCO LD-3331: "W15 hoặc W2" must keep both alternatives ----------

    @Test
    void viaResolver_acceptsVietnameseAlternativeRoute() throws Exception {
        AtfmViaResolver resolver = new AtfmViaResolver();
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:hoac-" + System.nanoTime() + ";MODE=Oracle")) {
            assertThat(resolver.resolve(connection, "SGN", "CXR", "VFC", "W15 hoặc W2"))
                    .isEqualTo("W15/W2");
            assertThat(resolver.resolve(connection, "SGN", "PXU", "VFC", "W12, Q1, W1"))
                    .isEqualTo("W12/Q1/W1");
        }
    }

    @Test
    void viaResolver_rejectsUnreadableAlternativeText() throws Exception {
        AtfmViaResolver resolver = new AtfmViaResolver();
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:hoac-bad-" + System.nanoTime() + ";MODE=Oracle")) {
            assertThatThrownBy(
                    () -> resolver.resolve(connection, "SGN", "CXR", "VFC", "đường bay dự bị"))
                    .isInstanceOf(AtfmDocumentRouteException.class);
        }
    }

    // ---------- FQA OF-6605: profile keeps ETA and permit date, operator stays FQA ----------

    @Test
    void fqaProfile_readsEtaPermitDateAndFixedOperator() {
        SchedulePermit permit = parseSampleOrSkip(
                "duongvtt_20260915_082025_email_000_OF-6605.9.2026VN_FQA162__16SEP26_gw_a25d1eda.docx");
        assertThat(permit.operatorId()).isEqualTo("FQA");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(permit.flights()).hasSize(1);
        ScheduleFlight flight = permit.flights().getFirst();
        assertThat(flight.flightNumber()).isEqualTo("FQA162");
        assertThat(flight.etd()).isEqualTo("0555");
        // The audited defects were operator/ETA/permit-date; B737-85P stays an
        // aircraft-review item (unresolved in M_CRAFT_TYPE) and the registration
        // cell "VT-AAZ OR SUB, VTBAZ" is left for manual confirmation.
        assertThat(flight.eta()).isEqualTo("1115");
        assertThat(flight.via()).contains("G474").contains("L628");
    }

    // ---------- HAI LD-3185 REV2: revision keeps BUS purpose ----------

    @Test
    void haiRevision_keepsBusPurposeAndNewRow() {
        SchedulePermit permit = parseSampleOrSkip(
                "anhkd_20260915_093048_email_001_LD-3185_rev2_HAI488_15sep_G17.44-260914-170129_22195371.docx");
        assertThat(permit.revision()).isTrue();
        assertThat(permit.flights()).isNotEmpty();
        ScheduleFlight revised = permit.flights().stream()
                .filter(flight -> "HAI488".equals(flight.flightNumber()))
                .findFirst()
                .orElseThrow();
        // The profile default must be BUS for this carrier (audit bug switched it to PAX).
        // Note: LBG (Long Thanh) is normalized to LFPB by the ICAO conversion step.
        assertThat(revised.purposeId()).isEqualTo("BUS");
        assertThat(revised.beginDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(revised.fromAirport()).isEqualTo("LFPB");
        assertThat(revised.toAirport()).isEqualTo("VVNB");
    }

    // ---------- SPQ LD-32 REV15: table 2.1 is ORIGINAL, 2.2 is REPLACEMENT ----------

    @Test
    void spa017Revision_splitsFiveOriginalAndFiveNew() {
        SchedulePermit permit = parseSampleOrSkip(
                "dungdm_20260915_095459_email_000__SPA017__REV15_LD-32B_S26_17SEP-29SEP_ND_4a031213.docx");
        assertThat(permit.operatorId()).isEqualTo("SPQ");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 0032B/S/CHK/2026");
        // The audit bug produced OLD=10 because the new table's column header
        // "Giờ dự kiến" matched the original-schedule marker.
        assertThat(permit.flights()).hasSize(5);
        assertThat(permit.originalFlights()).hasSize(5);
        assertThat(permit.flights())
                .allSatisfy(flight -> assertThat(flight.etd()).startsWith("07"));
        assertThat(permit.originalFlights())
                .anySatisfy(flight -> assertThat(flight.etd()).isEqualTo("0920"));
    }

    // ---------- OF-6517 KMI: operator, multi-day validity, both flights ----------

    @Test
    void of6517_readsKmiOperatorAndBothFlights() {
        SchedulePermit permit = parseSampleOrSkip(
                "duongvtt_20260911_085300_email_000_OF-6517.9.2026VN-_KMI301_05-24Oct2026_31620891.docx");
        assertThat(permit.operatorId()).isEqualTo("KMI");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(permit.flights()).hasSize(2);
        assertThat(permit.flights()).extracting(flight -> flight.flightNumber())
                .containsExactlyInAnyOrder("KMI301", "KMI302");
        ScheduleFlight outbound = permit.flights().stream()
                .filter(flight -> "KMI301".equals(flight.flightNumber()))
                .findFirst()
                .orElseThrow();
        assertThat(outbound.beginDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(outbound.endDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(outbound.etd()).isEqualTo("1445");
        // The generic base profile dropped the ETA column; the KMI profile must
        // keep it (audit: saved T_PERMDETAIL_SC rows had ETA null for OF-6517).
        assertThat(outbound.eta()).isEqualTo("1800");
        ScheduleFlight returnFlight = permit.flights().stream()
                .filter(flight -> "KMI302".equals(flight.flightNumber()))
                .findFirst()
                .orElseThrow();
        assertThat(returnFlight.eta()).isEqualTo("2235");
        assertThat(outbound.sourceAircraftType()).contains("B738");
    }

    private SchedulePermit parseSampleOrSkip(String fileName) {
        String configured = System.getProperty("permit.repair.sample.directory");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "Set -Dpermit.repair.sample.directory to parse the real 2026-09-15 documents");
        Path root = Path.of(configured);
        Path file;
        try (var paths = Files.walk(root)) {
            file = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            Assumptions.abort("Cannot read sample directory: " + exception.getMessage());
            return null;
        }
        Assumptions.assumeTrue(file != null, "Sample document missing: " + fileName);
        return parser.parse(file, fileName);
    }
}
