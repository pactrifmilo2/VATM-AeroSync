package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vatm.aerosync.worker.model.ScheduleFlight;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SeptemberCorpusProfilesTest {
    @ParameterizedTest
    @ValueSource(strings = {"CTJ", "TGW", "CPA", "TVJ", "FDX", "BKP", "CQH", "DLH", "HKE", "KQA",
            "OMA", "CSC", "CGN", "BCS", "ACP", "ETD", "CSH", "CES", "CSS", "CSN", "DKH", "AFL"})
    void readsSeasonalNumberHeadingWithoutInventingSeason(String carrier) {
        var doc = document(carrier, "NUMBER OF-28/A/W/2026VN", "3. Schedules: UTC Time");
        var profile = new WordPermitFormatDetector(new DocxPermitProfileCatalog()).detect(doc, "winter.docx");
        assertThat(profile.operator().fixedValue()).isEqualTo(carrier);
        var permit = new DocxSchedulePermitParser().parse(doc, "winter.docx");
        assertThat(permit.operatorId()).isEqualTo(carrier);
        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 0028A/W/CHK/2026");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(permit.seasonalPermit()).isTrue();
        assertThat(permit.flights()).singleElement().satisfies(f -> {
            assertThat(f.flightNumber()).isEqualTo(carrier + "100");
            assertThat(f.purposeId()).isEqualTo("CAR");
            assertThat(f.beginDate()).isEqualTo(LocalDate.of(2026, 10, 25));
            assertThat(f.endDate()).isEqualTo(LocalDate.of(2027, 3, 27));
            assertThat(f.fromAirport()).isEqualTo("VVNB");
            assertThat(f.toAirport()).isEqualTo("VHHH");
            assertThat(f.etd()).isEqualTo("0100");
            assertThat(f.eta()).isEqualTo("0300");
            assertThat(f.sourceAircraftType()).isEqualTo("B738");
            assertThat(f.via()).isEqualTo("A1");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TWY", "AJV", "LSM", "HAI", "SAZ", "SMR", "AOJ", "BCP", "ADB", "SXI", "CEO", "ARN", "BDJ", "JAA"})
    void readsPrivateCarrierWithoutIata(String carrier) {
        String prefix = List.of("SAZ", "SMR", "AOJ", "CEO", "ARN").contains(carrier) ? "LD" : "OF";
        var doc = document(carrier, "PERMIT NUMBER " + prefix + "-3450/9/2026VN", "2. Schedules: UTC Time");
        var permit = new DocxSchedulePermitParser().parse(doc, "permit.docx");
        assertThat(permit.operatorId()).isEqualTo(carrier);
        assertThat(permit.seasonalPermit()).isFalse();
        assertThat(permit.flights()).extracting(ScheduleFlight::etd).containsExactly("0100");
        assertThat(permit.flights().getFirst().sourceAircraftType()).isEqualTo("B738");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANA:OF", "KAL:OF", "RMY:OF", "CSN:LD", "HVN:LD", "KAL:LD", "ETH:LD", "VJC:LD"})
    void readsRevisionRoleProfiles(String identity) {
        String[] parts = identity.split(":");
        var base = document(parts[0], parts[1] + "-28/A/W/2026VN/REV2", "2.2 New schedule(s)");
        var permit = new DocxSchedulePermitParser().parse(base, "revision.docx");
        assertThat(permit.operatorId()).isEqualTo(parts[0]);
        assertThat(permit.seasonalRevision()).isTrue();
        assertThat(permit.flights()).hasSize(1);
        assertThat(permit.originalFlights()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CSG", "UEA"})
    void readsAdditionalSeasonalLandingProfiles(String carrier) {
        var permit = new DocxSchedulePermitParser().parse(
                document(carrier, "LD-88/A/S/2026VN", "Schedules: UTC Time"), "landing.docx");
        assertThat(permit.operatorId()).isEqualTo(carrier);
        assertThat(permit.seasonalPermit()).isTrue();
        assertThat(permit.flights()).hasSize(1);
    }

    @org.junit.jupiter.api.Test
    void readsDotSeparatedRevisionPermit() {
        var permit = new DocxSchedulePermitParser().parse(
                document("MRJ", "LD-3043.8.2026VN/REV1", "Schedules: UTC Time"), "revision.docx");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 03043/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("MRJ");
        assertThat(permit.revisionNumber()).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void readsUtcSuffixColumnsAndParenthesizedNextDayArrival() {
        var original = document("TGW", "OF-77/B/S/2026VN", "Schedules: UTC Time");
        var schedule = List.of(
                List.of("Flight number", "Effective from", "Effective to", "Days of services", "Departure Airport", "EOBT (UTC)", "Arrival Airport", "EIBT (UTC)"),
                List.of("TGW841", "01Sep26", "24Oct26", "1234567", "RKSI", "2350", "WSSS", "0615(+1)"));
        var doc = new WordPermitDocument(original.paragraphText(), original.tableText(), original.rawContent(),
                List.of(schedule, original.tables().get(1)), List.of("Schedules: UTC Time", "Aircraft"));
        var permit = new DocxSchedulePermitParser().parse(doc, "tgw.docx");
        assertThat(permit.flights().getFirst().etd()).isEqualTo("2350");
        assertThat(permit.flights().getFirst().eta()).isEqualTo("0615+");
    }

    @org.junit.jupiter.api.Test
    void readsParenthesizedNextDayArrivalWithoutExplicitOne() {
        var original = document("TGW", "OF-77/B/S/2026VN", "Schedules: UTC Time");
        var schedule = List.of(
                List.of("Flight number", "Effective from", "Effective to", "Days of services", "Departure Airport", "EOBT (UTC)", "Arrival Airport", "EIBT (UTC)"),
                List.of("IGO841", "01Sep26", "24Oct26", "1234567", "RKSI", "2030", "VABB", "0105(+)"));
        var doc = new WordPermitDocument(original.paragraphText(), original.tableText(), original.rawContent(),
                List.of(schedule, original.tables().get(1)), List.of("Schedules: UTC Time", "Aircraft"));
        var permit = new DocxSchedulePermitParser().parse(doc, "igo.docx");
        assertThat(permit.flights().getFirst().eta()).isEqualTo("0105+");
    }

    private WordPermitDocument document(String carrier, String number, String scheduleHeading) {
        String text = "HA NOI, 18 SEP 2026\n" + number + "\n1. Carrier\nName: Example airline\n"
                + "IATA Code: N/A\nICAO Code: " + carrier + "\n" + scheduleHeading
                + "\n4. Purpose of Flight(s): All Cargo\n5. Aircraft\n6. Airways";
        var schedule = List.of(
                List.of("Flight number", "Effective from", "Effective to", "Days of services", "Departure Airport", "EOBT", "Arrival Airport", "EIBT"),
                List.of(carrier + "100", "25Oct2026", "27Mar2027", "1234567", "VVNB", "0100", "VHHH", "0300"));
        return new WordPermitDocument(text, "", text,
                List.of(schedule, List.of(List.of("Aircraft Type"), List.of("B738")),
                        List.of(List.of("Sector", "Airways"), List.of("VVNB-VHHH", "A1"))),
                List.of(scheduleHeading, "5. Aircraft", "6. Airways"));
    }
}
