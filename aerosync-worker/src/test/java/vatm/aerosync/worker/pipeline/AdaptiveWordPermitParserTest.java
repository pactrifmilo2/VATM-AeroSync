package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveWordPermitParserTest {

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parseWithDiagnostics_acceptsSharedAliasAndMultiRowHeaderForReview() {
        WordPermitDocument document = permitDocument(true, true, true);

        WordPermitParseResult result =
                parser.parseWithDiagnostics(document, "adaptive-overflight.docx");

        assertThat(result.profileId()).isEqualTo("caav-english-overflight-scheduled");
        assertThat(result.profileVersion()).isEqualTo(1);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.permit().reviewOnly()).isTrue();
        assertThat(result.permit().operatorId()).isEqualTo("RMY");
        assertThat(result.permit().flights()).singleElement().satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo("RMY685");
            assertThat(flight.fromAirport()).isEqualTo("WMKK");
            assertThat(flight.toAirport()).isEqualTo("VHHH");
        });
        assertThat(result.warnings())
                .extracting(warning -> warning.code())
                .contains("ADAPTIVE_TABLE_HEADER", "MULTI_ROW_HEADER", "SHARED_ALIAS_USED");
        assertThat(result.fields())
                .filteredOn(field -> field.field().equals("schedule.flightNumber"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.method()).isEqualTo("SHARED_ALIAS");
                    assertThat(field.source()).contains("TABLE[2]").contains("HEADER[1..2]");
                    assertThat(field.confidence()).isEqualTo(0.95);
                });
    }

    @Test
    void parseWithDiagnostics_marksPartialProfileDetectionForReview() {
        WordPermitDocument document = permitDocument(false, false, false);

        WordPermitParseResult result =
                parser.parseWithDiagnostics(document, "partial-overflight.docx");

        assertThat(result.profileId()).isEqualTo("caav-english-overflight-scheduled");
        assertThat(result.confidence()).isCloseTo(0.9333, within(0.0001));
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.warnings())
                .anySatisfy(warning -> {
                    assertThat(warning.code()).isEqualTo("PROFILE_DETECTION_PARTIAL");
                    assertThat(warning.reviewRequired()).isTrue();
                });
        assertThat(result.candidates())
                .filteredOn(candidate ->
                        candidate.profileId().equals("caav-english-overflight-scheduled"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.matchedDetectionPatterns()).isEqualTo(2);
                    assertThat(candidate.detectionPatternCount()).isEqualTo(3);
                });
    }

    @Test
    void parseWithDiagnostics_keepsExactKnownHeaderImportReady() {
        WordPermitDocument document = permitDocument(true, false, false);

        WordPermitParseResult result =
                parser.parseWithDiagnostics(document, "known-overflight.docx");

        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.reviewRequired()).isFalse();
        assertThat(result.permit().reviewOnly()).isFalse();
        assertThat(result.fields())
                .filteredOn(field -> field.field().equals("schedule.flightNumber"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.method()).isEqualTo("DECLARED_ALIAS");
                    assertThat(field.confidence()).isEqualTo(1.0);
                });
    }

    @Test
    void tableMatcher_combinesTwoHeaderRowsWithoutConsumingData() {
        List<List<String>> table = multiRowSchedule(true);
        Map<String, List<String>> aliases = Map.of(
                "flightNumber", List.of("Call sign"),
                "effectiveFrom", List.of("Effective from"),
                "fromAirport", List.of("Departure Airport"));

        WordPermitTableMatcher.TableMatch match = WordPermitTableMatcher.find(
                List.of(table),
                List.of(""),
                aliases,
                aliases,
                List.of("flightNumber", "effectiveFrom", "fromAirport"),
                List.of(),
                List.of(),
                false);

        assertThat(match).isNotNull();
        assertThat(match.headerRows()).isEqualTo(2);
        assertThat(match.dataRows()).hasSize(1);
        assertThat(match.dataRows().getFirst().getFirst()).isEqualTo("RMY685");
        assertThat(match.columns()).containsEntry("flightNumber", 0)
                .containsEntry("effectiveFrom", 1)
                .containsEntry("fromAirport", 4);
    }

    @Test
    void profileCatalog_layersFamilyAndGlobalAliasesOverDeclaredProfile() {
        DocxPermitProfileCatalog catalog = new DocxPermitProfileCatalog();
        DocxPermitFormatProfile declared =
                catalog.declaredProfile("caav-english-overflight-scheduled");
        DocxPermitFormatProfile resolved = catalog.profiles().stream()
                .filter(profile -> profile.id().equals(declared.id()))
                .findFirst()
                .orElseThrow();

        assertThat(declared.family()).isEqualTo("caav-english");
        assertThat(declared.profileVersion()).isEqualTo(1);
        assertThat(declared.schedule().columns().get("flightNumber"))
                .doesNotContain("Call sign", "Flight identifier");
        assertThat(resolved.schedule().columns().get("flightNumber"))
                .contains("Call sign", "Flight identifier");
    }

    @Test
    void tableMatcher_acceptsConservativeTypoAsReviewRequiredFuzzyMatch() {
        Map<String, List<String>> aliases = Map.of(
                "flightNumber", List.of("Flight number"),
                "effectiveFrom", List.of("Effective from"));
        List<List<String>> table = List.of(
                List.of("Flight nummber", "Effective from"),
                List.of("RMY685", "20JUL26"));

        WordPermitTableMatcher.TableMatch match = WordPermitTableMatcher.find(
                List.of(table),
                List.of(""),
                aliases,
                aliases,
                List.of("flightNumber", "effectiveFrom"),
                List.of(),
                List.of(),
                false);

        assertThat(match).isNotNull();
        assertThat(match.requiresReview()).isTrue();
        assertThat(match.columnMatches().get("flightNumber").kind())
                .isEqualTo(WordPermitTableMatcher.MatchKind.FUZZY_ALIAS);
        assertThat(match.columnMatches().get("flightNumber").confidence())
                .isGreaterThanOrEqualTo(0.90);
    }

    private WordPermitDocument permitDocument(boolean cargoSignal,
                                              boolean multiRowHeader,
                                              boolean sharedFlightAlias) {
        String purpose = cargoSignal ? "Cargo flight" : "Freight operation";
        String paragraphs = String.join("\n",
                "HANOI, 17-Jul-26",
                "PERMIT NUMBER OF-5199/7/2026VN",
                "2. Billing address:",
                "3. Schedules: UTC Time",
                "4. Purpose of flight(s): " + purpose,
                "(Ref. G17.44-260715-170787)");

        List<List<List<String>>> tables = new ArrayList<>();
        tables.add(List.of(
                List.of("Name: RAYA AIRWAYS", "ICAO Code: RMY"),
                List.of("Postal address: Cyberjaya, Malaysia", "")));
        if (multiRowHeader) {
            tables.add(multiRowSchedule(sharedFlightAlias));
        } else {
            tables.add(List.of(
                    List.of(
                            sharedFlightAlias ? "Call sign" : "Flight number",
                            "Eff from",
                            "Eff to",
                            "Day(s) of services",
                            "Dep airport",
                            "ETD",
                            "Arr airport",
                            "ETA"),
                    scheduleValues()));
        }
        tables.add(List.of(
                List.of("Sector", "Airways"),
                List.of("WMKK - VHHH", "M765-M771")));

        String tableText = tables.stream()
                .flatMap(List::stream)
                .flatMap(List::stream)
                .collect(Collectors.joining("\n"));
        return new WordPermitDocument(
                paragraphs,
                tableText,
                paragraphs + "\n" + tableText,
                tables,
                Collections.nCopies(tables.size(), ""));
    }

    private List<List<String>> multiRowSchedule(boolean sharedFlightAlias) {
        return List.of(
                List.of(
                        sharedFlightAlias ? "Call" : "Flight",
                        "Effective",
                        "Effective",
                        "Day(s) of",
                        "Departure",
                        "",
                        "Arrival",
                        ""),
                List.of(
                        sharedFlightAlias ? "sign" : "number",
                        "from",
                        "to",
                        "services",
                        "airport",
                        "ETD",
                        "airport",
                        "ETA"),
                scheduleValues());
    }

    private List<String> scheduleValues() {
        return List.of(
                "RMY685",
                "20JUL26",
                "27JUL26",
                "1------",
                "WMKK",
                "1140",
                "VHHH",
                "1550");
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
